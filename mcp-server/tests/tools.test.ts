import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, describe, it } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import type { Config } from '../src/config.ts';
import { PaiAgentClient } from '../src/client.ts';
import { createServer } from '../src/server.ts';
import { FakePaiAgent, SUCCESS_EXECUTION } from './fake-paiagent.ts';
import { testConfig } from './helpers.ts';

interface Called {
  isError: boolean;
  data: Record<string, any>;
  text: string;
}

describe('MCP 工具（通过真实的 MCP 协议调用）', () => {
  const fake = new FakePaiAgent();
  let baseUrl = '';
  const cleanups: Array<() => Promise<void>> = [];

  before(async () => {
    baseUrl = await fake.start();
  });
  after(() => fake.close());
  beforeEach(() => {
    fake.mode = {};
    fake.startRequests.length = 0;
    fake.resumeRequests.length = 0;
    fake.cancelRequests.length = 0;
  });
  afterEach(async () => {
    while (cleanups.length > 0) {
      await cleanups.pop()!();
    }
  });

  async function connect(overrides: Partial<Config> = {}) {
    const config = testConfig(baseUrl, overrides);
    const server = createServer(config, new PaiAgentClient(config));
    const client = new Client({ name: 'test-client', version: '1' });
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    await Promise.all([server.connect(serverSide), client.connect(clientSide)]);
    cleanups.push(async () => {
      await client.close();
      await server.close();
    });
    const call = async (name: string, args: Record<string, unknown>): Promise<Called> => {
      const result = await client.callTool({ name, arguments: args });
      const content = result.content as Array<{ type: string; text: string }>;
      const text = content[0]?.text ?? '';
      let data: Record<string, any> = {};
      try {
        data = JSON.parse(text);
      } catch {
        // 参数校验失败时 SDK 返回的是纯文本
      }
      return { isError: result.isError === true, data, text };
    };
    return { client, call };
  }

  it('暴露 5 个工具，描述里写明什么时候用/不用，参数有 schema', async () => {
    const { client } = await connect();
    const { tools } = await client.listTools();

    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      ['cancel_execution', 'get_execution', 'list_workflows', 'resume_execution', 'start_workflow'],
    );
    for (const tool of tools) {
      assert.match(tool.description ?? '', /什么时候用/, `${tool.name} 缺少“什么时候用”`);
      assert.match(tool.description ?? '', /什么时候不用/, `${tool.name} 缺少“什么时候不用”`);
      assert.equal(tool.inputSchema.type, 'object');
    }
    const start = tools.find((tool) => tool.name === 'start_workflow')!;
    assert.deepEqual([...(start.inputSchema.required ?? [])].sort(), ['input_text', 'workflow_id']);
    assert.equal(start.annotations?.readOnlyHint, false);
    assert.equal(tools.find((tool) => tool.name === 'get_execution')!.annotations?.readOnlyHint, true);
    const cancel = tools.find((tool) => tool.name === 'cancel_execution')!;
    assert.equal(cancel.annotations?.destructiveHint, true, '取消会中断运行中的节点，要让客户端知道它不是无害的');
    assert.equal(cancel.annotations?.idempotentHint, true);
  });

  it('list_workflows：不返回 flowData，只给编号/名称/说明/输入标签/节点数', async () => {
    const { call } = await connect();
    const result = await call('list_workflows', {});

    assert.equal(result.isError, false);
    assert.equal(result.data.total, 3);
    assert.deepEqual(result.data.workflows[0], { id: 1, name: 'Java面试题讲解', description: '输入一个 Java 知识点，生成讲解', input_label: '用户问题', node_count: 3 });
    assert.ok(!result.text.includes('flowData') && !result.text.includes('sk-'), '不应把流程数据或密钥片段交给模型');
    assert.equal(result.data.workflows[2].description, '', 'flowData 解析不了时仍然返回基本信息');
  });

  it('list_workflows：keyword 过滤、limit 截断并标注', async () => {
    const { call } = await connect();

    const filtered = await call('list_workflows', { keyword: '视频' });
    assert.deepEqual(filtered.data.workflows.map((w: { id: number }) => w.id), [2]);

    const limited = await call('list_workflows', { limit: 1 });
    assert.equal(limited.data.returned, 1);
    assert.equal(limited.data.truncated, true);
    assert.equal(limited.data.total, 3);
  });

  it('list_workflows：keyword 无匹配时给出恢复提示', async () => {
    const { call } = await connect();

    const result = await call('list_workflows', { keyword: '不存在的工作流' });

    assert.equal(result.isError, false);
    assert.equal(result.data.ok, true);
    assert.equal(result.data.total, 0);
    assert.deepEqual(result.data.workflows, []);
    assert.match(result.data.hint, /未找到匹配/);
    assert.match(result.data.hint, /更换关键词/);
    assert.match(result.data.hint, /不传 keyword/);
  });

  it('start_workflow：立即返回 execution_id 和下一步指引', async () => {
    const { call } = await connect();
    const result = await call('start_workflow', { workflow_id: 1, input_text: '  讲讲 CHM  ' });

    assert.equal(result.isError, false);
    assert.equal(result.data.execution_id, 101);
    assert.equal(result.data.status, 'RUNNING');
    assert.match(result.data.next_step, /get_execution/);
    assert.equal(fake.startRequests[0]?.inputData, '讲讲 CHM', '输入应去掉首尾空白');
  });

  it('start_workflow：相同 request_id 重试不会重复提交', async () => {
    const { call } = await connect();
    const first = await call('start_workflow', { workflow_id: 1, input_text: '讲讲 CHM', request_id: 'job-1' });
    const second = await call('start_workflow', { workflow_id: 1, input_text: '讲讲 CHM', request_id: 'job-1' });

    assert.equal(fake.startRequests.length, 1, '后端只应收到一次提交');
    assert.equal(second.data.execution_id, first.data.execution_id);
    assert.equal(second.data.deduplicated, true);
  });

  it('start_workflow：request_id 配了不同的输入 → REQUEST_ID_REUSED', async () => {
    const { call } = await connect();
    await call('start_workflow', { workflow_id: 1, input_text: '任务 A', request_id: 'job-2' });
    const clash = await call('start_workflow', { workflow_id: 1, input_text: '任务 B', request_id: 'job-2' });

    assert.equal(clash.isError, true);
    assert.equal(clash.data.code, 'REQUEST_ID_REUSED');
    assert.equal(fake.startRequests.length, 1);
  });

  it('start_workflow：超长输入在协议层被拒绝，不会到达后端', async () => {
    const { call } = await connect({ maxInputChars: 10 });
    const result = await call('start_workflow', { workflow_id: 1, input_text: 'x'.repeat(11) });

    assert.equal(result.isError, true);
    assert.match(result.text, /input_text/);
    assert.equal(fake.startRequests.length, 0);
  });

  it('start_workflow：缺少必填参数、类型不对都被拒绝', async () => {
    const { call } = await connect();
    assert.equal((await call('start_workflow', { input_text: 'x' })).isError, true);
    assert.equal((await call('start_workflow', { workflow_id: '1', input_text: 'x' })).isError, true);
    assert.equal((await call('start_workflow', { workflow_id: 1, input_text: 'x', request_id: '带空格 的 id' })).isError, true);
    assert.equal(fake.startRequests.length, 0);
  });

  it('start_workflow：纯空白输入 → INVALID_INPUT', async () => {
    const { call } = await connect();
    const result = await call('start_workflow', { workflow_id: 1, input_text: '   ' });
    assert.equal(result.data.code, 'INVALID_INPUT');
  });

  it('后端 429 → {ok:false, code:BUSY, retryable:true, retry_after_seconds}', async () => {
    fake.mode = { busy: true };
    const { call } = await connect();
    const result = await call('start_workflow', { workflow_id: 1, input_text: 'x' });

    assert.equal(result.isError, true);
    assert.equal(result.data.ok, false);
    assert.equal(result.data.code, 'BUSY');
    assert.equal(result.data.retryable, true);
    assert.equal(result.data.retry_after_seconds, 10);
    assert.ok(result.data.hint);
  });

  it('工作流不存在 → NOT_FOUND', async () => {
    const { call } = await connect();
    assert.equal((await call('start_workflow', { workflow_id: 999, input_text: 'x' })).data.code, 'NOT_FOUND');
  });

  it('get_execution：RUNNING 时告诉模型多久后再查，并且别重复提交', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const running = await call('get_execution', { workflow_id: 1, execution_id: started.data.execution_id });

    assert.equal(running.data.status, 'RUNNING');
    assert.equal(running.data.done, false);
    assert.equal(running.data.retry_after_seconds, 4);
    assert.match(running.data.next_step, /不要再次调用 start_workflow/);
  });

  it('get_execution：完成后给摘要、产物链接；默认不带每个节点', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const id = started.data.execution_id;
    await call('get_execution', { workflow_id: 1, execution_id: id }); // 第一次轮询还是 RUNNING
    const done = await call('get_execution', { workflow_id: 1, execution_id: id });

    assert.equal(done.data.status, 'SUCCESS');
    assert.equal(done.data.done, true);
    assert.equal(done.data.duration_ms, 1234);
    assert.deepEqual(done.data.progress, { nodes_seen: 2, succeeded: 2, running: 0, failed: 0, cancelled: 0 });
    assert.deepEqual(done.data.artifacts, ['https://files.example.com/images/cover.png', 'https://files.example.com/videos/v.mp4']);
    assert.equal(done.data.nodes, undefined);
  });

  it('get_execution：detail=nodes 才返回节点，且不返回节点输入', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 3, input_text: 'x' });
    const detail = await call('get_execution', { workflow_id: 3, execution_id: started.data.execution_id, detail: 'nodes' });

    assert.equal(detail.data.nodes.length, 1);
    assert.equal(detail.data.nodes[0].status, 'FAILED');
    assert.ok(!('input' in detail.data.nodes[0]));
  });

  it('get_execution：最终输出超长时截断并标注', async () => {
    fake.scenarios.set(1, { runningPolls: 0, final: { ...SUCCESS_EXECUTION, outputData: 'A'.repeat(500) } });
    const { call } = await connect({ maxOutputChars: 200 });
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const done = await call('get_execution', { workflow_id: 1, execution_id: started.data.execution_id });
    fake.scenarios.set(1, { runningPolls: 1, final: SUCCESS_EXECUTION });

    assert.equal(done.data.output.length, 200);
    assert.equal(done.data.output_truncated, true);
  });

  it('get_execution：失败且是参数/密钥问题 → 告诉模型先让用户修配置，别盲目重试', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 3, input_text: 'x' });
    const failed = await call('get_execution', { workflow_id: 3, execution_id: started.data.execution_id });

    assert.equal(failed.data.status, 'FAILED');
    assert.equal(failed.data.failed_node.node_id, 'llm-1');
    assert.match(failed.data.failed_node.error, /Invalid API key/);
    assert.match(failed.data.next_step, /不要盲目重试/);
    assert.equal(failed.data.needs_decision, undefined);
  });

  it('get_execution：视频任务待决策 → needs_decision，并让模型把选项交给用户', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 2, input_text: 'x' });
    const failed = await call('get_execution', { workflow_id: 2, execution_id: started.data.execution_id });

    assert.equal(failed.data.needs_decision, true);
    assert.equal(failed.data.failed_node.error_code, 'VIDEO_TASK_PENDING');
    assert.match(failed.data.next_step, /continue.*regenerate/s);
    assert.match(failed.data.next_step, /用户/);
  });

  it('get_execution：编号不对 → NOT_FOUND', async () => {
    const { call } = await connect();
    assert.equal((await call('get_execution', { workflow_id: 1, execution_id: 424242 })).data.code, 'NOT_FOUND');
  });

  it('resume_execution：把用户的选择放进 modifiedVariables.videoTaskAction（与前端一致）', async () => {
    const { call } = await connect();
    const result = await call('resume_execution', { workflow_id: 2, execution_id: 105, video_task_action: 'continue' });

    assert.equal(result.isError, false);
    assert.deepEqual(fake.resumeRequests[0]?.body, {
      skipSuccessNodes: true,
      useSnapshotVariables: true,
      modifiedVariables: { videoTaskAction: 'continue' },
    });
    assert.equal(result.data.status, 'SUCCESS');
  });

  it('resume_execution：video_task_action 只接受 continue / regenerate', async () => {
    const { call } = await connect();
    assert.equal((await call('resume_execution', { workflow_id: 2, execution_id: 105, video_task_action: 'whatever' })).isError, true);
    assert.equal(fake.resumeRequests.length, 0);
  });

  it('resume_execution：后端 409 → CONFLICT，提示改用 get_execution', async () => {
    fake.mode = { resumeConflict: true };
    const { call } = await connect();
    const result = await call('resume_execution', { workflow_id: 2, execution_id: 105 });

    assert.equal(result.data.code, 'CONFLICT');
    assert.equal(result.data.retryable, false);
    assert.match(result.data.hint, /get_execution/);
  });

  it('resume_execution：续跑很快结束时，直接返回最终结果', async () => {
    const { call } = await connect();
    const result = await call('resume_execution', { workflow_id: 2, execution_id: 105 });
    assert.equal(result.data.status, 'SUCCESS');
    assert.equal(result.data.done, true);
  });

  it('resume_execution：长任务等不到结果时返回 RUNNING，让模型去轮询', async () => {
    fake.mode = { resumeDelayMs: 1_500 };
    const { call } = await connect({ resumeWaitMs: 300 });
    const result = await call('resume_execution', { workflow_id: 2, execution_id: 105 });

    assert.equal(result.isError, false);
    assert.equal(result.data.status, 'RUNNING');
    assert.equal(result.data.done, false);
    assert.equal(result.data.retry_after_seconds, 4);
    assert.match(result.data.next_step, /get_execution/);
  });

  it('cancel_execution：受理后是 CANCELLING（不是已停），get_execution 轮询到 CANCELLED', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const id = started.data.execution_id;

    const cancelled = await call('cancel_execution', { workflow_id: 1, execution_id: id });
    assert.equal(cancelled.isError, false);
    assert.equal(cancelled.data.status, 'CANCELLING');
    assert.equal(cancelled.data.done, false, 'CANCELLING 只是"已受理"，不能当作已经停了');
    assert.equal(cancelled.data.retry_after_seconds, 4);
    assert.match(cancelled.data.next_step, /get_execution/);

    const stopping = await call('get_execution', { workflow_id: 1, execution_id: id });
    assert.equal(stopping.data.status, 'CANCELLING');
    assert.equal(stopping.data.done, false);
    assert.match(stopping.data.next_step, /直到 status 变成 CANCELLED/);

    const stopped = await call('get_execution', { workflow_id: 1, execution_id: id });
    assert.equal(stopped.data.status, 'CANCELLED');
    assert.equal(stopped.data.done, true);
    assert.equal(stopped.data.progress.cancelled, 1);
    assert.match(stopped.data.next_step, /resume_execution/, '取消后要告诉模型还可以续跑');
    assert.equal(fake.cancelRequests.length, 1);
  });

  it('cancel_execution：重复调用是安全的（第二次直接得到已取消）', async () => {
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const id = started.data.execution_id;
    await call('cancel_execution', { workflow_id: 1, execution_id: id });
    await call('get_execution', { workflow_id: 1, execution_id: id });
    await call('get_execution', { workflow_id: 1, execution_id: id }); // 到达 CANCELLED

    const again = await call('cancel_execution', { workflow_id: 1, execution_id: id });

    assert.equal(again.isError, false);
    assert.equal(again.data.status, 'CANCELLED');
    assert.equal(again.data.done, true);
    assert.equal(again.data.retry_after_seconds, undefined);
  });

  it('cancel_execution：执行已经结束 → CONFLICT，提示是"已经结束"而不是"仍在运行"', async () => {
    fake.mode = { cancelTooLate: true };
    const { call } = await connect();
    const started = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
    const result = await call('cancel_execution', { workflow_id: 1, execution_id: started.data.execution_id });

    assert.equal(result.isError, true);
    assert.equal(result.data.code, 'CONFLICT');
    assert.equal(result.data.retryable, false);
    assert.match(result.data.hint, /已经结束/);
    assert.ok(!/仍在运行/.test(result.data.hint), '取消场景下不能沿用续跑那条"仍在运行"的提示');
  });

  it('cancel_execution：编号不对 → NOT_FOUND', async () => {
    const { call } = await connect();
    const result = await call('cancel_execution', { workflow_id: 1, execution_id: 424242 });
    assert.equal(result.data.code, 'NOT_FOUND');
  });

  it('白名单：不在 PAIAGENT_ALLOWED_WORKFLOWS 里的工作流看不到，也不能操作', async () => {
    const { call } = await connect({ allowedWorkflows: new Set([1]) });

    const listed = await call('list_workflows', {});
    assert.deepEqual(listed.data.workflows.map((w: { id: number }) => w.id), [1]);

    for (const [tool, args] of [
      ['start_workflow', { workflow_id: 2, input_text: 'x' }],
      ['get_execution', { workflow_id: 2, execution_id: 101 }],
      ['resume_execution', { workflow_id: 2, execution_id: 101 }],
      ['cancel_execution', { workflow_id: 2, execution_id: 101 }],
    ] as const) {
      const denied = await call(tool, args);
      assert.equal(denied.data.code, 'FORBIDDEN', tool);
    }
    assert.equal(fake.startRequests.length, 0);
    assert.equal(fake.resumeRequests.length, 0);
    assert.equal(fake.cancelRequests.length, 0);
  });

  it('后端不可用时，工具返回可重试的 UPSTREAM_UNAVAILABLE，而不是抛出异常', async () => {
    fake.mode = { gatewayDown: true };
    const { call } = await connect({ token: 'x', username: undefined, password: undefined });
    const result = await call('list_workflows', {});

    assert.equal(result.isError, true);
    assert.equal(result.data.code, 'UPSTREAM_UNAVAILABLE');
    assert.equal(result.data.retryable, true);
  });
});
