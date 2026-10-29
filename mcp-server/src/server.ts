import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import type { PaiAgentClient } from './client.ts';
import type { Config } from './config.ts';
import { ToolError, failure, success } from './errors.ts';
import { IdempotencyStore } from './idempotency.ts';
import { summarizeExecution, summarizeWorkflow } from './summarize.ts';

const INSTRUCTIONS = [
  'PaiAgent 是一个可视化的 AI 工作流平台。这个服务让你查看并运行其中已有的工作流。',
  '标准流程：list_workflows 找到工作流 → start_workflow 提交（立即返回 execution_id，不会等结果）→ 每隔 3~5 秒 get_execution 查询，直到 done 为 true。',
  '一次执行可能调用收费的大模型、生图、生视频接口：不要为了试一试而反复提交；同一个任务需要重试时，带上相同的 request_id。用户不想要了就用 cancel_execution 停掉，不要放着继续跑。',
  '所有工具出错时都返回 {ok:false, code, message, hint, retryable}，按 hint 行事；retryable 为 false 时不要自动重试。',
].join('\n');

const workflowId = z.number().int().positive().describe('工作流编号，来自 list_workflows');
const executionId = z.number().int().positive().describe('执行编号，来自 start_workflow 的返回');

export type Logger = (event: Record<string, unknown>) => void;

/** 日志只写 stderr（stdout 是协议通道），并且只记录编号、耗时和结果，不记录输入文本、令牌和密码。 */
export const stderrLogger: Logger = (event) => {
  process.stderr.write(`${JSON.stringify({ ts: new Date().toISOString(), ...event })}\n`);
};

export function createServer(config: Config, client: PaiAgentClient, log: Logger = () => {}): McpServer {
  const server = new McpServer({ name: 'paiagent', version: '0.1.0' }, { instructions: INSTRUCTIONS });
  const idempotency = new IdempotencyStore(config.idempotencyTtlMs);

  function assertAllowed(id: number): void {
    if (config.allowedWorkflows && !config.allowedWorkflows.has(id)) {
      throw new ToolError('FORBIDDEN', `工作流 ${id} 不在这个服务允许操作的范围内`, {
        hint: '只能操作 list_workflows 返回的工作流。如果确实需要，请让管理员把它加入 PAIAGENT_ALLOWED_WORKFLOWS',
      });
    }
  }

  /** 统一的收尾：计时、记日志、把任何异常变成结构化错误，工具本身只写正常路径。 */
  async function guarded(
    tool: string,
    context: Record<string, unknown>,
    run: () => Promise<Record<string, unknown>>,
  ): Promise<CallToolResult> {
    const started = Date.now();
    try {
      const payload = await run();
      log({ tool, ...context, outcome: 'ok', ms: Date.now() - started, ...(payload.execution_id ? { execution_id: payload.execution_id } : {}) });
      return success(payload);
    } catch (error) {
      const toolError = error instanceof ToolError
        ? error
        : new ToolError('INTERNAL', '工具内部出错', { hint: '这是服务本身的问题，请告诉用户，不要重试' });
      log({ tool, ...context, outcome: toolError.code, ms: Date.now() - started, ...(error instanceof ToolError ? {} : { internal_error: String(error) }) });
      return failure(toolError);
    }
  }

  server.registerTool(
    'list_workflows',
    {
      title: '列出工作流',
      description: [
        '列出 PaiAgent 里可以运行的工作流（编号、名称、说明、输入框的标签）。',
        '什么时候用：用户想运行某个工作流但没说编号，或者问“有哪些工作流”时。',
        '什么时候不用：已经知道工作流编号时直接 start_workflow；想查某次执行的结果用 get_execution。',
        '返回的 workflows 默认最多 20 条；truncated 为 true 说明还有更多，可以用 keyword 缩小范围。',
      ].join('\n'),
      inputSchema: {
        keyword: z.string().max(100).optional().describe('按名称或说明过滤（不区分大小写）'),
        limit: z.number().int().min(1).max(50).default(20).describe('最多返回几条，默认 20'),
      },
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    },
    ({ keyword, limit }) =>
      guarded('list_workflows', { has_keyword: Boolean(keyword) }, async () => {
        const all = await client.listWorkflows();
        const trimmedKeyword = keyword?.trim();
        const needle = trimmedKeyword?.toLowerCase();
        const visible = all
          .filter((workflow) => !config.allowedWorkflows || config.allowedWorkflows.has(workflow.id))
          .filter((workflow) => !needle || `${workflow.name} ${workflow.description ?? ''}`.toLowerCase().includes(needle));
        return {
          ok: true,
          total: visible.length,
          returned: Math.min(visible.length, limit),
          truncated: visible.length > limit,
          workflows: visible.slice(0, limit).map(summarizeWorkflow),
          ...(needle && visible.length === 0 ? {
            hint: `未找到匹配“${trimmedKeyword}”的工作流。请更换关键词，或不传 keyword 列出全部工作流`,
          } : {}),
        };
      }),
  );

  server.registerTool(
    'start_workflow',
    {
      title: '运行工作流',
      description: [
        '提交一次工作流执行，立即返回 execution_id，不等待结果（执行可能要几秒到几分钟）。',
        '什么时候用：用户要求运行、生成、处理某个已有工作流时。用户已经给出工作流编号就直接调用，不需要先 list_workflows 确认；只有不知道编号时才先 list_workflows。',
        '什么时候不用：只是想看有哪些工作流（用 list_workflows）；想知道结果（用 get_execution）；失败后想续跑（用 resume_execution）。',
        '注意：每次执行都可能调用收费的大模型 / 生图 / 生视频接口，不要为了测试而反复提交。',
        '重试同一个任务时必须带相同的 request_id，这样即使上一次其实已经提交成功，也不会被重复执行。',
        `下一步：每隔 3~5 秒调用 get_execution，直到 done 为 true。`,
      ].join('\n'),
      inputSchema: {
        workflow_id: workflowId,
        input_text: z.string().min(1).max(config.maxInputChars)
          .describe(`交给工作流输入节点的文本，不超过 ${config.maxInputChars} 个字符`),
        request_id: z.string().min(1).max(100).regex(/^[\w.:-]+$/, '只能包含字母、数字、下划线、点、冒号和连字符').optional()
          .describe('可选。同一个任务重试时传相同的值（例如 "task-42"），避免重复提交；不同任务必须用不同的值'),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    },
    ({ workflow_id, input_text, request_id }) =>
      guarded('start_workflow', { workflow_id, input_chars: input_text.length, has_request_id: Boolean(request_id) }, async () => {
        assertAllowed(workflow_id);
        const text = input_text.trim();
        if (!text) {
          throw new ToolError('INVALID_INPUT', 'input_text 不能是空白', { hint: '让用户提供要处理的内容' });
        }
        const submit = () => client.startExecution(workflow_id, text);
        const { result, deduplicated } = request_id
          ? await idempotency.run(workflow_id, request_id, IdempotencyStore.fingerprint(text), submit)
          : { result: await submit(), deduplicated: false };
        return {
          ok: true,
          workflow_id,
          execution_id: result.executionId,
          status: result.status,
          ...(deduplicated ? { deduplicated: true, note: '这个 request_id 之前已经提交过，返回的是原来那次执行，没有重复运行' } : {}),
          next_step: '每隔 3~5 秒调用 get_execution（带上 workflow_id 和 execution_id），直到 done 为 true。',
          retry_after_seconds: 4,
        };
      }),
  );

  server.registerTool(
    'get_execution',
    {
      title: '查询执行状态与结果',
      description: [
        '查询一次执行的状态和结果。执行中也可以查询。',
        '什么时候用：start_workflow 或 resume_execution 之后轮询；用户问“跑完了吗 / 结果是什么 / 为什么失败”。',
        '什么时候不用：想重新运行（用 start_workflow）。',
        '默认只返回摘要：状态、进度、失败的节点、最终输出（过长会截断并标注）、产物链接。需要看每个节点时设置 detail 为 "nodes"。',
        'done 为 false 时按 retry_after_seconds 等待后再查；done 为 true 看 status 和 next_step。',
      ].join('\n'),
      inputSchema: {
        workflow_id: workflowId,
        execution_id: executionId,
        detail: z.enum(['summary', 'nodes']).default('summary').describe('summary（默认）只给摘要；nodes 额外返回每个节点的状态和输出预览'),
      },
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    },
    ({ workflow_id, execution_id, detail }) =>
      guarded('get_execution', { workflow_id, execution_id, detail }, async () => {
        assertAllowed(workflow_id);
        const execution = await client.getExecution(workflow_id, execution_id);
        return summarizeExecution(workflow_id, execution, detail, config.maxOutputChars);
      }),
  );

  server.registerTool(
    'resume_execution',
    {
      title: '续跑失败的执行',
      description: [
        '从失败处继续一次失败的执行：只重跑失败的节点，已成功的节点直接复用之前的结果（不会重复计费）。',
        '什么时候用：get_execution 显示 status 为 FAILED，并且原因是超时、服务繁忙等临时问题；或者 next_step 要求你在两个选项里让用户选一个之后。',
        '什么时候不用：错误是参数、模型名、密钥不对（先让用户修正配置）；执行仍在运行（用 get_execution 等）；想用新的输入重新开始（用 start_workflow）。',
        '视频节点提示“上次的视频任务还没取回结果”（needs_decision 为 true）时：必须先让用户在 continue（继续等待上次任务）和 regenerate（重新提交，会产生新费用）里选，再把选择填进 video_task_action。不要替用户选。',
        '续跑可能要几分钟：本工具最多等待几秒，如果返回 status 为 RUNNING，就用 get_execution 轮询。',
      ].join('\n'),
      inputSchema: {
        workflow_id: workflowId,
        execution_id: executionId,
        video_task_action: z.enum(['continue', 'regenerate']).optional()
          .describe('仅当失败原因是视频任务待决策时填写，值必须来自用户的选择'),
        modified_variables: z.record(z.string(), z.unknown()).optional()
          .describe('可选。续跑前要改写的变量，键是变量名；一般不需要'),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    },
    ({ workflow_id, execution_id, video_task_action, modified_variables }) =>
      guarded('resume_execution', { workflow_id, execution_id, video_task_action }, async () => {
        assertAllowed(workflow_id);
        const variables = { ...modified_variables, ...(video_task_action ? { videoTaskAction: video_task_action } : {}) };
        const outcome = await client.resumeExecution(
          workflow_id,
          execution_id,
          {
            skipSuccessNodes: true,
            useSnapshotVariables: true,
            ...(Object.keys(variables).length > 0 ? { modifiedVariables: variables } : {}),
          },
          config.resumeWaitMs,
        );
        if (outcome === 'STILL_RUNNING') {
          return summarizeExecution(workflow_id, { executionId: execution_id, status: 'RUNNING' }, 'summary', config.maxOutputChars);
        }
        return summarizeExecution(workflow_id, outcome, 'summary', config.maxOutputChars);
      }),
  );

  server.registerTool(
    'cancel_execution',
    {
      title: '取消正在运行的执行',
      description: [
        '取消一次正在运行的执行：不再启动后面的节点，并中断正在运行的节点。取消前已经完成的节点的结果保留，之后可以用 resume_execution 从断点继续。',
        '什么时候用：用户明确说"不要了 / 取消 / 停掉"，或者发现输入提交错了、不想再继续花钱时。',
        '什么时候不用：只是想知道进度（用 get_execution）；执行已经结束（SUCCESS / FAILED 没有可取消的内容）；想用新输入重新运行（用 start_workflow）。',
        '返回的 status 为 CANCELLING 表示"已受理、节点正在退出"，不等于已经停了：每隔几秒用 get_execution 查看，直到 status 变成 CANCELLED。重复调用是安全的。',
        '注意：已经发给大模型 / 生图 / 生视频服务商的请求，对方可能已经开始计费，取消不能撤回；取消是协作式的，不响应中断的节点要等它自己返回。',
      ].join('\n'),
      inputSchema: {
        workflow_id: workflowId,
        execution_id: executionId,
      },
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
    },
    ({ workflow_id, execution_id }) =>
      guarded('cancel_execution', { workflow_id, execution_id }, async () => {
        assertAllowed(workflow_id);
        let outcome;
        try {
          outcome = await client.cancelExecution(workflow_id, execution_id);
        } catch (error) {
          if (error instanceof ToolError && error.code === 'CONFLICT') {
            // 后端对"已经结束"的执行返回 409；对取消来说，这不是"仍在运行"，提示要换成对应的
            throw new ToolError('CONFLICT', error.message, {
              hint: '这次执行已经结束，没有可以取消的内容。用 get_execution 查看它的最终结果',
            });
          }
          throw error;
        }
        const cancelled = outcome.status === 'CANCELLED';
        return {
          ok: true,
          workflow_id,
          execution_id,
          status: outcome.status,
          done: cancelled,
          ...(outcome.message ? { message: outcome.message } : {}),
          next_step: cancelled
            ? '已取消。取消前已完成的节点结果保留；用户之后想继续可以调用 resume_execution。'
            : '已受理取消，节点正在退出。4 秒后调用 get_execution 查看，直到 status 变成 CANCELLED；不需要再次取消。',
          ...(cancelled ? {} : { retry_after_seconds: 4 }),
        };
      }),
  );

  return server;
}
