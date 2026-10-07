import { test } from 'node:test';
import assert from 'node:assert/strict';
import { describeResumeFailure, parseModifiedVariables } from '../src/utils/resumeRequest.ts';

test('empty text means "no modification"', () => {
  assert.deepEqual(parseModifiedVariables('   '), { ok: true, value: undefined });
});

test('accepts a JSON object', () => {
  assert.deepEqual(parseModifiedVariables('{"input": "新的输入", "n": 2}'), { ok: true, value: { input: '新的输入', n: 2 } });
});

test('rejects invalid JSON and non-object JSON with a readable message', () => {
  const bad = parseModifiedVariables('{input:');
  assert.equal(bad.ok, false);
  for (const text of ['[1,2]', '"str"', '42', 'null']) {
    const r = parseModifiedVariables(text);
    assert.equal(r.ok, false, text);
  }
});

test('409 and 429 get specific, actionable messages', () => {
  assert.match(describeResumeFailure(409, 'x'), /仍在运行/);
  assert.match(describeResumeFailure(429, 'x'), /繁忙/);
  assert.equal(describeResumeFailure(500, '后端报错'), '后端报错');
  assert.equal(describeResumeFailure(undefined, undefined), '断点续执行失败');
});
