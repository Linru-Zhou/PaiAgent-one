/**
 * 小评测：让一个真实的 Agent（Claude Code，无头模式）只凭工具描述去完成一组自然语言任务，
 * 记录它是否选对了工具、参数是否正确、碰到失败时的处理是否符合预期。
 *
 *   node scripts/eval.ts                 # 跑全部任务
 *   node scripts/eval.ts --only T05,T06  # 只跑指定任务
 *   node scripts/eval.ts --model haiku   # 换模型（默认 haiku，描述写得不好的话小模型更容易暴露问题）
 *
 * 对端是 tests/fake-paiagent.ts 里的假后端（行为可控，不花钱、不依赖外网），
 * 所以这里测的是“工具层的设计”（描述、参数、错误信息能否被模型正确使用），不是 PaiAgent 本身。
 * 结果写到 eval/results.json。每个任务只跑一次，样本很小，数字只能说明大致情况，不能当作统计结论。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { FakePaiAgent, credentials } from '../tests/fake-paiagent.ts';

interface Call {
  tool: string;
  args: Record<string, any>;
  result: Record<string, any> | string | undefined;
}

interface RunResult {
  calls: Call[];
  finalText: string;
  /** 整段对话里模型说过的所有文字（含调用工具之前的）；用户在对话里能看到这些，只看最后一条会漏判 */
  allText: string;
  turns: number;
  costUsd: number | undefined;
  durationMs: number;
  timedOut: boolean;
  /** claude 命令自己报错（例如没登录），这时任务结果没有意义 */
  cliError: string | undefined;
}

interface Check {
  name: string;
  pass: boolean;
}

interface Task {
  id: string;
  title: string;
  prompt: string;
  setup?: (fake: FakePaiAgent) => void;
  check: (run: RunResult, fake: FakePaiAgent) => Check[];
}

const names = (run: RunResult) => run.calls.map((call) => call.tool);
const count = (run: RunResult, tool: string) => run.calls.filter((call) => call.tool === tool).length;
const starts = (run: RunResult) => run.calls.filter((call) => call.tool === 'start_workflow');
const mentions = (run: RunResult, pattern: RegExp) => pattern.test(run.finalText);
/** “先告诉我 id，再查状态”：id 通常在调用 get_execution 之前的那条消息里，所以在整段对话文字里找，且必须是后端真实返回的那个 id */
const saidExecutionId = (run: RunResult) => {
  const id = (starts(run)[0]?.result as Record<string, unknown> | undefined)?.execution_id;
  return id !== undefined && new RegExp(`\\b${String(id)}\\b`).test(run.allText);
};

const tasks: Task[] = [
  {
    id: 'T01',
    title: '只是问有哪些工作流',
    prompt: 'PaiAgent 里现在有哪些工作流可以用？',
    check: (run, fake) => [
      { name: '调用了 list_workflows', pass: count(run, 'list_workflows') >= 1 },
      { name: '没有提交任何执行', pass: fake.startRequests.length === 0 },
      { name: '回答里提到了“视频”工作流', pass: mentions(run, /视频/) },
    ],
  },
  {
    id: 'T02',
    title: '按名称运行（没给编号）',
    prompt: '帮我运行「Java面试题讲解」这个工作流，输入内容是：ConcurrentHashMap 的 size() 为什么不精确。跑完把结果告诉我。',
    check: (run, fake) => [
      { name: '先 list_workflows 找编号', pass: names(run).indexOf('list_workflows') !== -1 && names(run).indexOf('list_workflows') < names(run).indexOf('start_workflow') },
      { name: '用对了工作流编号 1', pass: starts(run)[0]?.args.workflow_id === 1 },
      { name: 'input_text 是用户给的内容', pass: /ConcurrentHashMap/.test(String(starts(run)[0]?.args.input_text)) },
      { name: '只提交了一次', pass: fake.startRequests.length === 1 },
      { name: '用 get_execution 轮询到完成', pass: run.calls.some((call) => call.tool === 'get_execution' && call.result && (call.result as Record<string, any>).done === true) },
      { name: '回答里有结果内容', pass: mentions(run, /CounterCell/) },
    ],
  },
  {
    id: 'T03',
    title: '直接给了编号',
    prompt: '运行工作流 1，输入：讲讲 HashMap 的扩容机制。完成后告诉我最终输出。',
    check: (run, fake) => [
      { name: '没有多余的 list_workflows', pass: count(run, 'list_workflows') === 0 },
      { name: '提交了一次且编号正确', pass: fake.startRequests.length === 1 && fake.startRequests[0]?.workflowId === 1 },
      { name: '没有在 RUNNING 时重复提交', pass: starts(run).length === 1 },
      { name: '回答里有结果内容', pass: mentions(run, /CounterCell/) },
    ],
  },
  {
    id: 'T04',
    title: '查询已有执行',
    prompt: '工作流 1 的第 101 次执行结果怎么样了？（先帮我提交过的，不要重新运行）',
    setup: (fake) => {
      fake.seedExecution(101, 1);
    },
    check: (run, fake) => [
      { name: '调用了 get_execution 且参数正确', pass: run.calls.some((call) => call.tool === 'get_execution' && call.args.workflow_id === 1 && call.args.execution_id === 101) },
      { name: '没有重新提交', pass: fake.startRequests.length === 0 },
    ],
  },
  {
    id: 'T05',
    title: '失败：模型密钥无效（不该盲目重试）',
    prompt: '运行工作流 3，输入：你好。把结果告诉我；如果失败了，告诉我原因和下一步该怎么办。',
    check: (run, fake) => [
      { name: '提交了一次', pass: fake.startRequests.length === 1 },
      { name: '没有调用 resume_execution 去盲目重试', pass: count(run, 'resume_execution') === 0 },
      { name: '回答里指出是密钥/API Key 问题', pass: mentions(run, /(API\s*Key|密钥|key)/i) },
      { name: '建议让用户检查/修正配置', pass: mentions(run, /(检查|修正|配置|更新|重新设置)/) },
    ],
  },
  {
    id: 'T06',
    title: '视频任务待决策（不该替用户选）',
    prompt: '运行工作流 2，输入：讲讲 volatile 关键字。出结果后告诉我情况。',
    check: (run, fake) => [
      { name: '提交了一次', pass: fake.startRequests.length === 1 },
      { name: '没有替用户做选择去 resume', pass: fake.resumeRequests.length === 0 },
      { name: '回答里把“继续等待 / 重新生成”两个选项交给用户', pass: mentions(run, /继续/) && mentions(run, /重新/) },
      { name: '提到重新生成会产生新费用', pass: mentions(run, /(费用|计费|收费|花钱)/) },
    ],
  },
  {
    id: 'T07',
    title: '用户已选择继续等待',
    prompt: '工作流 2 的第 105 次执行因为视频任务待决策失败了。我的选择是：继续等待上次的任务。请帮我续跑，并告诉我结果。',
    setup: (fake) => {
      fake.seedExecution(105, 2);
    },
    check: (run, fake) => [
      { name: '调用了 resume_execution', pass: fake.resumeRequests.length >= 1 },
      { name: 'video_task_action 是 continue', pass: (fake.resumeRequests[0]?.body.modifiedVariables as Record<string, unknown> | undefined)?.videoTaskAction === 'continue' },
      { name: '没有重新提交', pass: fake.startRequests.length === 0 },
    ],
  },
  {
    id: 'T08',
    title: '服务繁忙（429）后的处理',
    prompt: '运行工作流 1，输入：解释一下 synchronized 和 ReentrantLock 的区别。',
    setup: (fake) => {
      fake.mode = { busyFirst: 1 };
    },
    check: (run, fake) => [
      { name: '提交次数不超过 3（没有疯狂重试）', pass: fake.startRequests.length <= 3 },
      { name: '最终要么成功要么如实报告', pass: run.calls.some((call) => call.tool === 'get_execution') || mentions(run, /(繁忙|稍后|重试)/) },
      { name: '重试时没有换输入', pass: starts(run).every((call) => call.args.input_text === starts(run)[0]?.args.input_text) },
    ],
  },
  {
    id: 'T09',
    title: '工作流编号不存在',
    prompt: '运行工作流 999，输入：你好。',
    check: (run, fake) => [
      { name: '没有成功提交任何执行', pass: fake.startRequests.length <= 1 },
      { name: '回答里说明编号不存在，或列出可用工作流', pass: mentions(run, /(不存在|没有这个|找不到|可用)/) || count(run, 'list_workflows') >= 1 },
    ],
  },
  {
    id: 'T10',
    title: '要求取消一次正在运行的执行',
    prompt: '工作流 1 的第 101 次执行不想要了，帮我取消掉，并告诉我现在的状态。',
    setup: (fake) => {
      fake.seedExecution(101, 1);
    },
    check: (run, fake) => [
      { name: '调用了 cancel_execution 且参数正确', pass: run.calls.some((call) => call.tool === 'cancel_execution' && call.args.workflow_id === 1 && call.args.execution_id === 101) },
      { name: '没有用 start/resume 去"糊弄"', pass: fake.startRequests.length === 0 && fake.resumeRequests.length === 0 },
      { name: '没有把 CANCELLING 当成已经停了：用 get_execution 确认过，或如实说"正在取消"', pass: count(run, 'get_execution') >= 1 || mentions(run, /(正在取消|取消中|已受理|CANCELLING)/) },
      { name: '同一次取消没有被反复提交（最多 2 次）', pass: fake.cancelRequests.length <= 2 },
    ],
  },
  {
    id: 'T13',
    title: '取消时执行其实已经结束了',
    prompt: '帮我取消工作流 1 的第 101 次执行。如果取消不了，告诉我原因。',
    setup: (fake) => {
      fake.seedExecution(101, 1);
      fake.mode = { cancelTooLate: true };
    },
    check: (run, fake) => [
      { name: '尝试过取消', pass: fake.cancelRequests.length >= 1 },
      { name: '没有不停重试取消（CONFLICT 不可重试）', pass: fake.cancelRequests.length <= 2 },
      { name: '回答里说明执行已经结束', pass: mentions(run, /(已经结束|已结束|已完成|无法取消|没有可以取消)/) },
      { name: '没有重新提交或续跑', pass: fake.startRequests.length === 0 && fake.resumeRequests.length === 0 },
    ],
  },
  {
    id: 'T11',
    title: '输入超长',
    prompt: `运行工作流 1，输入内容是下面这段话重复 400 遍：「请讲讲 Java 内存模型。」（把它们拼接成一个整体作为输入提交）`,
    check: (run, fake) => [
      { name: '没有向后端提交超长输入', pass: fake.startRequests.every((request) => request.inputData.length <= 2000) },
      { name: '回答里说明了长度限制或做了缩减', pass: mentions(run, /(2000|限制|太长|过长|缩短|精简)/) || fake.startRequests.length > 0 },
    ],
  },
  {
    id: 'T12',
    title: '提交后询问进度（不要重复提交）',
    prompt: '运行工作流 1，输入：什么是 happens-before。提交之后先别等，告诉我 execution_id 就行；然后再确认它现在是什么状态。',
    check: (run, fake) => [
      { name: '只提交了一次', pass: fake.startRequests.length === 1 },
      { name: '对话里给出了 start_workflow 返回的 execution_id', pass: saidExecutionId(run) },
      { name: '用 get_execution 查了状态', pass: count(run, 'get_execution') >= 1 },
    ],
  },
];

function parseStream(stdout: string): RunResult {
  const calls: Call[] = [];
  const pending = new Map<string, Call>();
  let finalText = '';
  const texts: string[] = [];
  let turns = 0;
  let costUsd: number | undefined;
  let durationMs = 0;
  let cliError: string | undefined;

  for (const line of stdout.split('\n')) {
    if (!line.trim().startsWith('{')) {
      continue;
    }
    let message: any;
    try {
      message = JSON.parse(line);
    } catch {
      continue;
    }
    if (message.type === 'assistant') {
      for (const block of message.message?.content ?? []) {
        if (block.type === 'text' && typeof block.text === 'string') {
          texts.push(block.text);
        }
        if (block.type === 'tool_use') {
          const call: Call = { tool: String(block.name).replace(/^mcp__paiagent__/, ''), args: block.input ?? {}, result: undefined };
          calls.push(call);
          pending.set(block.id, call);
        }
      }
    } else if (message.type === 'user') {
      for (const block of message.message?.content ?? []) {
        if (block.type === 'tool_result') {
          const call = pending.get(block.tool_use_id);
          const raw = Array.isArray(block.content) ? block.content.map((part: any) => part.text ?? '').join('') : String(block.content ?? '');
          if (call) {
            try {
              call.result = JSON.parse(raw);
            } catch {
              call.result = raw;
            }
          }
        }
      }
    } else if (message.type === 'result') {
      finalText = String(message.result ?? '');
      turns = Number(message.num_turns ?? 0);
      costUsd = typeof message.total_cost_usd === 'number' ? message.total_cost_usd : undefined;
      durationMs = Number(message.duration_ms ?? 0);
      if (message.is_error === true) {
        cliError = finalText;
      }
    }
  }
  return { calls, finalText, allText: texts.join('\n'), turns, costUsd, durationMs, timedOut: false, cliError };
}

function runClaude(prompt: string, mcpConfig: string, model: string): Promise<RunResult> {
  return new Promise((resolve) => {
    const args = [
      '-p', prompt,
      '--mcp-config', mcpConfig,
      '--strict-mcp-config',
      '--tools', '',
      '--allowedTools', 'mcp__paiagent__list_workflows mcp__paiagent__start_workflow mcp__paiagent__get_execution mcp__paiagent__resume_execution mcp__paiagent__cancel_execution',
      '--output-format', 'stream-json',
      '--verbose',
      '--max-turns', '14',
      '--model', model,
      '--no-session-persistence',
      '--disable-slash-commands',
      '--append-system-prompt', '你在帮用户操作 PaiAgent。只使用提供的工具；不确定时如实说明，不要编造结果。',
    ];
    const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
    });
    const timer = setTimeout(() => child.kill('SIGKILL'), 240_000);
    let stderr = '';
    child.stderr.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    });
    child.on('close', () => {
      clearTimeout(timer);
      if (process.env.EVAL_DEBUG) {
        process.stderr.write(`\n--- claude stdout ---\n${stdout}\n--- claude stderr ---\n${stderr}\n`);
      }
      const parsed = parseStream(stdout);
      resolve(parsed.finalText === '' && parsed.calls.length === 0 ? { ...parsed, timedOut: true } : parsed);
    });
  });
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  const only = argv.includes('--only') ? new Set(argv[argv.indexOf('--only') + 1]!.split(',')) : undefined;
  const model = argv.includes('--model') ? argv[argv.indexOf('--model') + 1]! : 'haiku';

  const fake = new FakePaiAgent();
  const baseUrl = await fake.start();
  const mcpConfig = JSON.stringify({
    mcpServers: {
      paiagent: {
        command: process.execPath,
        args: [fileURLToPath(new URL('../src/index.ts', import.meta.url))],
        env: { PAIAGENT_BASE_URL: baseUrl, PAIAGENT_USERNAME: credentials.username, PAIAGENT_PASSWORD: credentials.password },
      },
    },
  });

  const report: Array<Record<string, unknown>> = [];
  for (const task of tasks.filter((candidate) => !only || only.has(candidate.id))) {
    fake.reset();
    task.setup?.(fake);
    process.stdout.write(`${task.id} ${task.title} ... `);
    const run = await runClaude(task.prompt, mcpConfig, model);
    if (run.cliError) {
      console.log('失败');
      await fake.close();
      console.error(`\nclaude 命令无法运行任务：${run.cliError}\n`
        + '无头模式需要命令行里的 claude 已登录（桌面版的登录状态不会共享给命令行）。\n'
        + '先在终端执行 claude auth login 完成登录（命令里不要加反引号），或者设置 ANTHROPIC_API_KEY 环境变量，再重新运行本脚本。\n'
        + '如果登录报 ECONNRESET：命令行程序不读 macOS 的系统代理，先 export HTTPS_PROXY=http://127.0.0.1:7890（端口以你的代理软件为准）再登录。');
      process.exit(2);
    }
    const checks = run.timedOut ? [{ name: '运行未完成（超时或启动失败）', pass: false }] : task.check(run, fake);
    const passed = checks.filter((check) => check.pass).length;
    console.log(`${passed}/${checks.length}  工具调用: ${names(run).join(' → ') || '（无）'}`);
    for (const check of checks.filter((candidate) => !candidate.pass)) {
      console.log(`    ✗ ${check.name}`);
    }
    report.push({
      id: task.id,
      title: task.title,
      prompt: task.prompt,
      passed,
      total: checks.length,
      checks,
      tool_calls: run.calls.map((call) => ({ tool: call.tool, args: call.args, ok: (call.result as Record<string, any> | undefined)?.ok, code: (call.result as Record<string, any> | undefined)?.code })),
      turns: run.turns,
      cost_usd: run.costUsd,
      duration_ms: run.durationMs,
      final_answer: run.finalText,
    });
  }
  await fake.close();

  const allChecks = report.flatMap((entry) => entry.checks as Check[]);
  const summary = {
    model,
    tasks: report.length,
    tasks_all_checks_passed: report.filter((entry) => entry.passed === entry.total).length,
    checks_passed: allChecks.filter((check) => check.pass).length,
    checks_total: allChecks.length,
    total_tool_calls: report.reduce((sum, entry) => sum + (entry.tool_calls as unknown[]).length, 0),
    note: '每个任务只跑一次；样本很小，数字只反映大致情况。',
  };
  console.log('\n汇总:', JSON.stringify(summary));
  if (!only) {
    mkdirSync(fileURLToPath(new URL('../eval', import.meta.url)), { recursive: true });
    writeFileSync(fileURLToPath(new URL('../eval/results.json', import.meta.url)), `${JSON.stringify({ summary, tasks: report }, null, 2)}\n`);
  }
}

await main();
