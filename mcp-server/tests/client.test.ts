import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, describe, it } from 'node:test';
import { PaiAgentClient } from '../src/client.ts';
import { ToolError } from '../src/errors.ts';
import { FakePaiAgent } from './fake-paiagent.ts';
import { testConfig } from './helpers.ts';

async function rejectsWith(promise: Promise<unknown>, code: string): Promise<ToolError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ToolError, `期望 ToolError，实际 ${String(error)}`);
    assert.equal(error.code, code, error.message);
    return error;
  }
  assert.fail(`期望抛出 ${code}，但调用成功了`);
}

describe('PaiAgentClient', () => {
  const fake = new FakePaiAgent();
  let baseUrl = '';
  before(async () => {
    baseUrl = await fake.start();
  });
  after(() => fake.close());
  beforeEach(() => {
    fake.mode = {};
    fake.loginRequests.length = 0;
    fake.startRequests.length = 0;
    fake.expireTokens();
  });
  afterEach(() => {
    fake.mode = {};
  });

  it('登录一次，之后复用令牌', async () => {
    const client = new PaiAgentClient(testConfig(baseUrl));
    await client.listWorkflows();
    await client.listWorkflows();
    await client.listWorkflows();
    assert.equal(fake.loginRequests.length, 1);
  });

  it('令牌过期（401）后自动重新登录并重试一次', async () => {
    const client = new PaiAgentClient(testConfig(baseUrl));
    await client.listWorkflows();
    fake.expireTokens();
    const workflows = await client.listWorkflows();
    assert.equal(workflows.length, 3);
    assert.equal(fake.loginRequests.length, 2);
  });

  it('多个并发请求同时发现令牌失效，只登录一次', async () => {
    const client = new PaiAgentClient(testConfig(baseUrl));
    await Promise.all([client.listWorkflows(), client.listWorkflows(), client.listWorkflows(), client.listWorkflows()]);
    assert.equal(fake.loginRequests.length, 1);
  });

  it('只配了静态令牌且被拒绝：AUTH_FAILED，并且不会去登录', async () => {
    const client = new PaiAgentClient(testConfig(baseUrl, { username: undefined, password: undefined, token: 'stale' }));
    const error = await rejectsWith(client.listWorkflows(), 'AUTH_FAILED');
    assert.equal(error.retryable, false);
    assert.equal(fake.loginRequests.length, 0);
  });

  it('密码错误：AUTH_FAILED，不可重试', async () => {
    const client = new PaiAgentClient(testConfig(baseUrl, { password: 'wrong' }));
    const error = await rejectsWith(client.listWorkflows(), 'AUTH_FAILED');
    assert.match(error.message, /登录 PaiAgent 失败/);
    assert.equal(error.retryable, false);
  });

  it('业务码 429 → BUSY，可重试并给出等待时间', async () => {
    fake.mode = { busy: true };
    const error = await rejectsWith(new PaiAgentClient(testConfig(baseUrl)).startExecution(1, 'x'), 'BUSY');
    assert.equal(error.retryable, true);
    assert.equal(error.retryAfterSeconds, 10);
  });

  it('业务码 409 → CONFLICT，不可重试', async () => {
    fake.mode = { resumeConflict: true };
    const error = await rejectsWith(new PaiAgentClient(testConfig(baseUrl)).resumeExecution(1, 101, {}, 1_000), 'CONFLICT');
    assert.equal(error.retryable, false);
  });

  it('“不存在” → NOT_FOUND', async () => {
    await rejectsWith(new PaiAgentClient(testConfig(baseUrl)).startExecution(999, 'x'), 'NOT_FOUND');
  });

  it('网关返回非 JSON 的 502 → UPSTREAM_UNAVAILABLE，可重试', async () => {
    fake.mode = { gatewayDown: true };
    const error = await rejectsWith(new PaiAgentClient(testConfig(baseUrl, { token: 'x' })).listWorkflows(), 'UPSTREAM_UNAVAILABLE');
    assert.equal(error.retryable, true);
  });

  it('连不上后端：即使是提交请求也报 UPSTREAM_UNAVAILABLE（请求肯定没被处理，可以重试）', async () => {
    const dead = new FakePaiAgent();
    const deadUrl = await dead.start();
    await dead.close();
    const client = new PaiAgentClient(testConfig(deadUrl, { token: 'x' }));
    const error = await rejectsWith(client.startExecution(1, 'x'), 'UPSTREAM_UNAVAILABLE');
    assert.equal(error.retryable, true);
    assert.match(error.hint ?? '', /没有被处理/);
  });

  it('提交请求超时 → UNKNOWN_OUTCOME，不可重试（可能已经开始执行了）', async () => {
    fake.mode = { hangStart: true };
    const client = new PaiAgentClient(testConfig(baseUrl, { submitTimeoutMs: 300 }));
    const error = await rejectsWith(client.startExecution(1, 'x'), 'UNKNOWN_OUTCOME');
    assert.equal(error.retryable, false);
    assert.match(error.hint ?? '', /get_execution/);
  });

  it('普通查询超时 → UPSTREAM_UNAVAILABLE（查询没有副作用，可以重试）', async () => {
    const hung = new FakePaiAgent();
    hung.mode = { hangStart: true };
    const url = await hung.start();
    try {
      // 该假后端只有提交会挂住；这里直接用一个永不响应的 fetch 验证分类
      const never: typeof fetch = (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal?.reason));
        });
      const client = new PaiAgentClient(testConfig(url, { token: 'x', requestTimeoutMs: 200 }), never);
      const error = await rejectsWith(client.listWorkflows(), 'UPSTREAM_UNAVAILABLE');
      assert.equal(error.retryable, true);
    } finally {
      await hung.close();
    }
  });

  it('续跑等不到结果：返回 STILL_RUNNING，而不是报错', async () => {
    fake.mode = { resumeDelayMs: 1_500 };
    const client = new PaiAgentClient(testConfig(baseUrl));
    assert.equal(await client.resumeExecution(1, 101, {}, 300), 'STILL_RUNNING');
  });
});
