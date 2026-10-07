import { test } from 'node:test';
import assert from 'node:assert/strict';
import { diffExecutions, stableStringify } from '../src/utils/executionDiff.ts';

const run = (nodes: Array<[string, string, unknown]>, outputData: unknown = { output: 'x' }) => ({
  status: 'SUCCESS',
  duration: 10,
  outputData,
  nodeResults: nodes.map(([nodeId, status, output]) => ({ nodeId, nodeName: nodeId, status, output })),
});

test('identical executions are consistent with identical outputs', () => {
  const r = diffExecutions(run([['in', 'SUCCESS', { v: 1 }], ['out', 'SUCCESS', { v: 2 }]]),
    run([['in', 'SUCCESS', { v: 1 }], ['out', 'SUCCESS', { v: 2 }]]));
  assert.equal(r.consistent, true);
  assert.equal(r.outputsIdentical, true);
  assert.ok(r.rows.every((row) => row.verdict === 'same'));
});

test('different LLM text is flagged but does not make the engines inconsistent', () => {
  const r = diffExecutions(run([['llm', 'SUCCESS', { output: 'answer A' }]], { output: 'answer A' }),
    run([['llm', 'SUCCESS', { output: 'answer B' }]], { output: 'answer B' }));
  assert.equal(r.consistent, true);
  assert.equal(r.outputsIdentical, false);
  assert.equal(r.rows[0].verdict, 'output-differs');
});

test('a node with a different status makes the run inconsistent', () => {
  const r = diffExecutions(run([['a', 'SUCCESS', {}]]), run([['a', 'FAILED', {}]]));
  assert.equal(r.consistent, false);
  assert.equal(r.rows[0].verdict, 'status-differs');
});

test('a node executed by only one engine makes the run inconsistent', () => {
  const r = diffExecutions(run([['a', 'SUCCESS', {}], ['b', 'SUCCESS', {}]]), run([['a', 'SUCCESS', {}]]));
  assert.equal(r.consistent, false);
  assert.deepEqual(r.rows.map((x) => x.verdict), ['same', 'only-in-a']);
  const reverse = diffExecutions(run([['a', 'SUCCESS', {}]]), run([['a', 'SUCCESS', {}], ['b', 'SUCCESS', {}]]));
  assert.deepEqual(reverse.rows.map((x) => x.verdict), ['same', 'only-in-b']);
});

test('key order and per-run volatile fields do not count as differences', () => {
  assert.equal(stableStringify({ b: 1, a: { d: 1, c: 2 } }), stableStringify({ a: { c: 2, d: 1 }, b: 1 }));
  assert.equal(
    stableStringify({ x: 1, __executionId__: 5, __idempotencyKey__: 'a' }),
    stableStringify({ x: 1, __executionId__: 99 }),
  );
});
