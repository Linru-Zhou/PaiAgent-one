import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ToolError } from '../src/errors.ts';
import { IdempotencyStore } from '../src/idempotency.ts';

const fp = IdempotencyStore.fingerprint;

describe('IdempotencyStore', () => {
  it('同一个 request_id + 同样的输入只提交一次', async () => {
    const store = new IdempotencyStore(60_000);
    let submits = 0;
    const submit = async () => ({ executionId: 7, status: 'RUNNING' + ++submits });

    const first = await store.run(1, 'task-1', fp('hello'), submit);
    const second = await store.run(1, 'task-1', fp('hello'), submit);

    assert.equal(submits, 1);
    assert.equal(first.deduplicated, false);
    assert.equal(second.deduplicated, true);
    assert.equal(second.result.executionId, 7);
  });

  it('并发的两次相同提交也只发一次', async () => {
    const store = new IdempotencyStore(60_000);
    let submits = 0;
    const submit = async () => {
      submits++;
      await new Promise((resolve) => setTimeout(resolve, 50));
      return { executionId: 9, status: 'RUNNING' };
    };

    const [a, b] = await Promise.all([
      store.run(1, 'task-2', fp('x'), submit),
      store.run(1, 'task-2', fp('x'), submit),
    ]);

    assert.equal(submits, 1);
    assert.deepEqual([a.deduplicated, b.deduplicated].sort(), [false, true]);
  });

  it('不同工作流可以用相同的 request_id', async () => {
    const store = new IdempotencyStore(60_000);
    let submits = 0;
    const submit = async () => ({ executionId: ++submits, status: 'RUNNING' });
    await store.run(1, 'same', fp('x'), submit);
    await store.run(2, 'same', fp('x'), submit);
    assert.equal(submits, 2);
  });

  it('同一个 request_id 配了不同的输入：REQUEST_ID_REUSED，不会悄悄返回别人的执行', async () => {
    const store = new IdempotencyStore(60_000);
    await store.run(1, 'task-3', fp('A'), async () => ({ executionId: 1, status: 'RUNNING' }));
    await assert.rejects(
      store.run(1, 'task-3', fp('B'), async () => ({ executionId: 2, status: 'RUNNING' })),
      (error: unknown) => error instanceof ToolError && error.code === 'REQUEST_ID_REUSED',
    );
  });

  it('明确失败（请求没被处理）后，可以用同一个 request_id 重试', async () => {
    const store = new IdempotencyStore(60_000);
    let attempts = 0;
    const flaky = async () => {
      if (++attempts === 1) {
        throw new ToolError('BUSY', '忙', { retryable: true });
      }
      return { executionId: 5, status: 'RUNNING' };
    };

    await assert.rejects(store.run(1, 'task-4', fp('x'), flaky));
    const retry = await store.run(1, 'task-4', fp('x'), flaky);

    assert.equal(attempts, 2);
    assert.equal(retry.result.executionId, 5);
  });

  it('结果未知（超时）后，同一个 request_id 不允许再发：避免重复执行', async () => {
    const store = new IdempotencyStore(60_000);
    let attempts = 0;
    const timesOut = async () => {
      attempts++;
      throw new ToolError('UNKNOWN_OUTCOME', '超时');
    };

    await assert.rejects(store.run(1, 'task-5', fp('x'), timesOut));
    await assert.rejects(
      store.run(1, 'task-5', fp('x'), timesOut),
      (error: unknown) => error instanceof ToolError && error.code === 'UNKNOWN_OUTCOME',
    );
    assert.equal(attempts, 1, '第二次不应该再去提交');
  });

  it('记录过期后可以重新提交', async () => {
    let now = 1_000;
    const store = new IdempotencyStore(100, () => now);
    let submits = 0;
    const submit = async () => ({ executionId: ++submits, status: 'RUNNING' });
    await store.run(1, 'task-6', fp('x'), submit);
    now += 101;
    const again = await store.run(1, 'task-6', fp('x'), submit);
    assert.equal(again.deduplicated, false);
    assert.equal(submits, 2);
  });
});
