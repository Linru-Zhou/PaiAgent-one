/**
 * 把每一种错误码的真实返回打印出来（用测试用的假后端制造各种失败，服务本身是真的）。
 *
 *   node scripts/show-error-examples.ts
 *
 * 想弄清“模型到底会收到什么”时看这个：每个错误都是同一个结构
 * {ok:false, code, message, hint, retryable, retry_after_seconds?}，isError 为 true。
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { PaiAgentClient } from '../src/client.ts';
import type { Config } from '../src/config.ts';
import { createServer } from '../src/server.ts';
import { FakePaiAgent } from '../tests/fake-paiagent.ts';
import { testConfig } from '../tests/helpers.ts';

const fake = new FakePaiAgent();
const baseUrl = await fake.start();

async function call(overrides: Partial<Config>, name: string, args: Record<string, unknown>) {
  const config = testConfig(baseUrl, overrides);
  const server = createServer(config, new PaiAgentClient(config));
  const client = new Client({ name: 'error-examples', version: '1' });
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverSide), client.connect(clientSide)]);
  const result = await client.callTool({ name, arguments: args });
  await client.close();
  await server.close();
  return { isError: result.isError === true, text: (result.content as Array<{ text: string }>)[0]?.text ?? '' };
}

function show(title: string, when: string, outcome: { isError: boolean; text: string }) {
  console.log(`\n### ${title}`);
  console.log(`触发：${when}`);
  console.log(`isError = ${outcome.isError}`);
  try {
    console.log(JSON.stringify(JSON.parse(outcome.text), null, 2));
  } catch {
    console.log(outcome.text);
  }
}

fake.mode = { busy: true };
show('BUSY', '后端执行队列满了，返回业务码 429', await call({}, 'start_workflow', { workflow_id: 1, input_text: '讲讲 HashMap' }));

fake.mode = { hangStart: true };
show('UNKNOWN_OUTCOME', '提交请求超时：不知道后端有没有开始执行', await call({ submitTimeoutMs: 300 }, 'start_workflow', { workflow_id: 1, input_text: '讲讲 HashMap', request_id: 'job-1' }));

fake.seedExecution(101, 1);
fake.mode = { cancelTooLate: true };
show('CONFLICT', '取消时这次执行已经结束，后端返回业务码 409', await call({}, 'cancel_execution', { workflow_id: 1, execution_id: 101 }));

fake.mode = {};
show('NOT_FOUND', '工作流或执行编号不存在', await call({}, 'get_execution', { workflow_id: 1, execution_id: 424242 }));
show('FORBIDDEN', '不在 PAIAGENT_ALLOWED_WORKFLOWS 白名单里', await call({ allowedWorkflows: new Set([1]) }, 'start_workflow', { workflow_id: 2, input_text: 'x' }));
show('AUTH_FAILED', '账号密码不对', await call({ password: 'wrong' }, 'list_workflows', {}));
show('INVALID_INPUT', '输入只有空白（长度、类型等错误在协议层就被拒绝，返回的是纯文本）', await call({}, 'start_workflow', { workflow_id: 1, input_text: '   ' }));
show('（协议层）参数校验失败', 'input_text 超过 2000 个字符，请求根本不会到达后端', await call({ maxInputChars: 10 }, 'start_workflow', { workflow_id: 1, input_text: 'x'.repeat(11) }));

await fake.close();
