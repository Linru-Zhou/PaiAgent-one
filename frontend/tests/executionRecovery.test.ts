import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RecoveryTracker } from '../src/utils/executionRecovery.ts';
import { diffExecutions, toDiffExecution } from '../src/utils/executionDiff.ts';

const node = (nodeId: string, status: string, extra: Record<string, unknown> = {}) => ({ nodeId, nodeName: nodeId, status, ...extra });

test('only events missed during the disconnect are replayed', () => {
  const t = new RecoveryTracker();
  // 断线前已经收到：a 开始并成功，b 开始
  t.observe({ eventType: 'NODE_START', nodeId: 'a' });
  t.observe({ eventType: 'NODE_SUCCESS', nodeId: 'a' });
  t.observe({ eventType: 'NODE_START', nodeId: 'b' });

  const { events, terminal } = t.next({
    status: 'SUCCESS', duration: 900,
    nodeResults: [node('a', 'SUCCESS'), node('b', 'SUCCESS', { duration: 300, output: '{"v":1}' }), node('c', 'SUCCESS', { duration: 5 })],
    outputData: '{"output":"done"}',
  });

  assert.deepEqual(events.map((e) => `${e.eventType}:${e.nodeId ?? ''}`),
    ['NODE_SUCCESS:b', 'NODE_START:c', 'NODE_SUCCESS:c', 'WORKFLOW_COMPLETE:']);
  assert.equal(terminal, true);
  assert.deepEqual((events[0].data as { output: unknown }).output, { v: 1 }, 'JSON 字符串被还原成对象');
  assert.deepEqual(events.at(-1)!.data, { output: 'done' });
  assert.match(events.at(-1)!.message!, /总耗时 900ms/);
});

test('a still-running execution yields progress events and is not terminal; repeated polls do not duplicate', () => {
  const t = new RecoveryTracker();
  const poll = { status: 'RUNNING', nodeResults: [node('a', 'SUCCESS', { duration: 3 }), node('b', 'RUNNING')] };

  const first = t.next(poll);
  assert.equal(first.terminal, false);
  assert.deepEqual(first.events.map((e) => `${e.eventType}:${e.nodeId}`), ['NODE_START:a', 'NODE_SUCCESS:a', 'NODE_START:b']);

  assert.deepEqual(t.next(poll).events, [], '同样的轮询结果不应重复补发');

  const second = t.next({ status: 'RUNNING', nodeResults: [node('a', 'SUCCESS'), node('b', 'SUCCESS', { duration: 9 })] });
  assert.deepEqual(second.events.map((e) => `${e.eventType}:${e.nodeId}`), ['NODE_SUCCESS:b']);
});

test('a failed run replays the node error, then completes with the failure message', () => {
  const t = new RecoveryTracker();
  const { events, terminal } = t.next({
    status: 'FAILED', duration: 40, errorMessage: '节点 b 执行失败',
    nodeResults: [node('a', 'SUCCESS'), node('b', 'FAILED', { error: 'boom' })],
  });
  assert.equal(terminal, true);
  const err = events.find((e) => e.eventType === 'NODE_ERROR')!;
  assert.equal(err.message, 'boom');
  assert.equal(events.at(-1)!.status, 'FAILED');
  assert.equal(events.at(-1)!.message, '节点 b 执行失败');
});

test('toDiffExecution parses JSON strings so engines are compared structurally, not textually', () => {
  const dag = toDiffExecution({ status: 'SUCCESS', outputData: '{"a":1,"b":2}', nodeResults: [{ nodeId: 'x', status: 'SUCCESS', output: '{"k":1,"j":2}' }] });
  const lg = toDiffExecution({ status: 'SUCCESS', outputData: '{"b":2,"a":1}', nodeResults: [{ nodeId: 'x', status: 'SUCCESS', output: '{"j":2,"k":1}' }] });
  const report = diffExecutions(dag, lg);
  assert.equal(report.outputsIdentical, true, '仅键顺序不同，不应判为不一致');
});

test('CANCELLING is not terminal: keep polling until the nodes have actually exited', () => {
  const t = new RecoveryTracker();
  const stopping = t.next({ status: 'CANCELLING', nodeResults: [node('a', 'SUCCESS', { duration: 3 }), node('b', 'RUNNING')] });

  assert.equal(stopping.terminal, false, '取消已受理但节点还在退出，不能当作结束');
  assert.ok(!stopping.events.some((e) => e.eventType === 'WORKFLOW_COMPLETE'));
});

test('a cancelled execution ends with CANCELLED; the interrupted node is reported as cancelled, not as a plain failure', () => {
  const t = new RecoveryTracker();
  const done = t.next({
    status: 'CANCELLED', duration: 1500,
    errorMessage: '执行已取消（已完成 1 个节点，其余未启动或已中断；可以从断点继续执行）',
    nodeResults: [node('a', 'SUCCESS', { duration: 3 }), node('b', 'CANCELLED', { error: '执行已取消' })],
  });

  assert.equal(done.terminal, true);
  const nodeError = done.events.find((e) => e.eventType === 'NODE_ERROR');
  assert.equal(nodeError?.status, 'CANCELLED');
  assert.match(nodeError!.message!, /执行已取消/, '界面靠这句文案把它显示成"已取消"而不是"失败"');
  const complete = done.events.at(-1)!;
  assert.equal(complete.eventType, 'WORKFLOW_COMPLETE');
  assert.equal(complete.status, 'CANCELLED');
  assert.match(complete.message!, /已取消/);
});
