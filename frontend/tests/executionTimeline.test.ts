import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTimeline, parseTimestamp } from '../src/utils/executionTimeline.ts';

const snap = (nodeId: string, start: string, end: string, duration: number, order: number, status = 'SUCCESS') => ({
  nodeId, nodeName: nodeId.toUpperCase(), status, startedAt: start, completedAt: end, duration, executionOrder: order,
});

test('three parallel 300ms nodes: wall time ~300ms, serial sum 900ms, speedup ~3x, concurrency 3', () => {
  const t = buildTimeline([
    snap('in', '2026-10-07T10:00:00.000', '2026-10-07T10:00:00.004', 4, 0),
    snap('a', '2026-10-07T10:00:00.010', '2026-10-07T10:00:00.312', 300, 1),
    snap('b', '2026-10-07T10:00:00.011', '2026-10-07T10:00:00.315', 300, 2),
    snap('c', '2026-10-07T10:00:00.012', '2026-10-07T10:00:00.316', 300, 3),
    snap('out', '2026-10-07T10:00:00.320', '2026-10-07T10:00:00.323', 3, 4),
  ]);
  assert.equal(t.maxConcurrency, 3);
  assert.equal(t.wallMs, 323);
  assert.equal(t.sumMs, 907);
  assert.ok(t.speedup > 2.7 && t.speedup < 3, `speedup ${t.speedup}`);
  assert.deepEqual(t.bars.map((b) => b.nodeId), ['in', 'a', 'b', 'c', 'out']);
});

test('strictly sequential nodes: no parallelism, speedup 1, back-to-back nodes are not counted as concurrent', () => {
  const t = buildTimeline([
    snap('a', '2026-10-07T10:00:00.000', '2026-10-07T10:00:00.100', 100, 0),
    snap('b', '2026-10-07T10:00:00.100', '2026-10-07T10:00:00.200', 100, 1),
  ]);
  assert.equal(t.maxConcurrency, 1);
  assert.equal(t.speedup, 1);
  assert.equal(t.wallMs, 200);
});

test('nodes without a start time (skipped / not reached) are listed separately, not drawn', () => {
  const t = buildTimeline([
    snap('a', '2026-10-07T10:00:00.000', '2026-10-07T10:00:00.050', 50, 0),
    { nodeId: 'skipped', nodeName: 'S', status: 'SKIPPED', executionOrder: 1 },
  ]);
  assert.equal(t.bars.length, 1);
  assert.deepEqual(t.notRun, [{ nodeId: 'skipped', label: 'S', status: 'SKIPPED' }]);
});

test('a still-running node without completedAt falls back to its duration; empty input is safe', () => {
  const t = buildTimeline([
    { nodeId: 'a', status: 'RUNNING', startedAt: '2026-10-07T10:00:00.000', duration: 120, executionOrder: 0 },
  ]);
  assert.equal(t.bars[0].endMs, 120);
  assert.deepEqual(buildTimeline([]).bars, []);
  assert.equal(buildTimeline([]).speedup, 1);
});

test('accepts Jackson array timestamps and keeps millisecond precision', () => {
  const a = parseTimestamp([2026, 10, 7, 10, 0, 0, 123_000_000]);
  const b = parseTimestamp('2026-10-07T10:00:00.123');
  assert.equal(a, b);
  assert.equal(parseTimestamp('nonsense'), null);
  assert.equal(parseTimestamp(null), null);
});
