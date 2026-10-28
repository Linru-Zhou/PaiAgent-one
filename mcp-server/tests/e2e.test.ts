import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { FakePaiAgent, credentials } from './fake-paiagent.ts';

const ENTRY = fileURLToPath(new URL('../src/index.ts', import.meta.url));

/**
 * 端到端：把服务当成一个真正的 stdio 子进程启动（Claude Code 等客户端就是这样用它的），
 * 用官方 SDK 的客户端走完整的 initialize → tools/list → tools/call。
 */
describe('stdio 端到端', () => {
  const fake = new FakePaiAgent();
  let client: Client;
  let stderr = '';
  const protocolErrors: Error[] = [];

  before(async () => {
    const baseUrl = await fake.start();
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [ENTRY],
      env: {
        PAIAGENT_BASE_URL: baseUrl,
        PAIAGENT_USERNAME: credentials.username,
        PAIAGENT_PASSWORD: credentials.password,
        PAIAGENT_ALLOWED_WORKFLOWS: '1,2,3',
      },
      stderr: 'pipe',
    });
    transport.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    });
    client = new Client({ name: 'e2e-client', version: '1' });
    // 服务端往 stdout 写了任何非协议内容，SDK 解析时会走到这里
    client.onerror = (error) => protocolErrors.push(error);
    await client.connect(transport);
  });

  after(async () => {
    await client.close();
    await fake.close();
  });

  async function call(name: string, args: Record<string, unknown>) {
    const result = await client.callTool({ name, arguments: args });
    const content = result.content as Array<{ text: string }>;
    return { isError: result.isError === true, data: JSON.parse(content[0]!.text) as Record<string, any> };
  }

  it('握手后报告服务端信息和使用说明', () => {
    assert.equal(client.getServerVersion()?.name, 'paiagent');
    assert.match(client.getInstructions() ?? '', /start_workflow/);
  });

  it('完整流程：列出 → 提交 → 轮询到完成', async () => {
    const listed = await call('list_workflows', { keyword: '讲解' });
    assert.equal(listed.data.workflows[0].id, 1);

    const started = await call('start_workflow', { workflow_id: 1, input_text: 'ConcurrentHashMap 的 size 为什么不精确', request_id: 'e2e-1' });
    assert.equal(started.data.status, 'RUNNING');

    let view = await call('get_execution', { workflow_id: 1, execution_id: started.data.execution_id });
    for (let i = 0; i < 5 && !view.data.done; i++) {
      view = await call('get_execution', { workflow_id: 1, execution_id: started.data.execution_id });
    }
    assert.equal(view.data.status, 'SUCCESS');
    assert.ok(view.data.artifacts.length >= 1);
  });

  it('失败样例：后端返回 429 时，错误结构完整', async () => {
    fake.mode = { busy: true };
    try {
      const busy = await call('start_workflow', { workflow_id: 1, input_text: 'x' });
      assert.equal(busy.isError, true);
      assert.deepEqual(
        Object.keys(busy.data).sort(),
        ['code', 'hint', 'message', 'ok', 'retry_after_seconds', 'retryable'],
      );
    } finally {
      fake.mode = {};
    }
  });

  it('stdout 上没有协议以外的内容', () => {
    assert.deepEqual(protocolErrors.map(String), []);
  });

  it('日志在 stderr，且不含密码、令牌和输入文本', () => {
    assert.match(stderr, /"event":"started"/);
    assert.match(stderr, /"tool":"start_workflow"/);
    assert.ok(!stderr.includes(credentials.password), 'stderr 里出现了密码');
    assert.ok(!/tok-\d+/.test(stderr), 'stderr 里出现了令牌');
    assert.ok(!stderr.includes('ConcurrentHashMap'), 'stderr 里出现了输入文本');
  });
});

describe('启动配置检查', () => {
  it('缺少凭据时拒绝启动，并说明怎么改', async () => {
    const { spawnSync } = await import('node:child_process');
    const result = spawnSync(process.execPath, [ENTRY], {
      env: { PATH: process.env.PATH ?? '', PAIAGENT_BASE_URL: 'http://localhost:1' },
      encoding: 'utf8',
      timeout: 10_000,
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /PAIAGENT_USERNAME/);
    assert.equal(result.stdout, '');
  });
});
