import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SseParser } from '../src/utils/sseParser.ts';

const collect = (chunks: string[]) => {
  const parser = new SseParser();
  return chunks.flatMap((c) => parser.push(c));
};

// Spring SseEmitter 的真实写法：冒号后没有空格
const STREAM =
  'event:WORKFLOW_START\ndata:{"eventType":"WORKFLOW_START","data":42}\n\n' +
  'event:NODE_START\ndata:{"nodeId":"a"}\n\n' +
  ': keep-alive\n\n' +
  'event:WORKFLOW_COMPLETE\ndata:{"status":"SUCCESS"}\n\n';

test('parses several events delivered in one chunk', () => {
  const messages = collect([STREAM]);
  assert.deepEqual(messages.map((m) => m.event), ['WORKFLOW_START', 'NODE_START', 'WORKFLOW_COMPLETE']);
  assert.equal(JSON.parse(messages[0].data).data, 42);
});

test('result is identical no matter where the network splits the stream (every 2-way split)', () => {
  const expected = collect([STREAM]);
  for (let i = 1; i < STREAM.length; i++) {
    assert.deepEqual(collect([STREAM.slice(0, i), STREAM.slice(i)]), expected, `split at ${i}`);
  }
});

test('result is identical when the stream arrives one character at a time', () => {
  assert.deepEqual(collect([...STREAM]), collect([STREAM]));
});

test('handles CRLF, including \\r and \\n landing in different chunks', () => {
  const crlf = STREAM.replace(/\n/g, '\r\n');
  const expected = collect([STREAM]);
  assert.deepEqual(collect([crlf]), expected);
  assert.deepEqual(collect([...crlf]), expected);
});

test('joins multi-line data with newlines and ignores a single leading space', () => {
  const [m] = collect(['event: note\ndata: line1\ndata: line2\n\n']);
  assert.equal(m.event, 'note');
  assert.equal(m.data, 'line1\nline2');
});

test('an event is only emitted once its terminating blank line arrives', () => {
  const parser = new SseParser();
  assert.deepEqual(parser.push('event:A\ndata:1\n'), []);
  assert.equal(parser.push('\n').length, 1);
});

test('defaults the event name to "message" and ignores comments / unknown fields', () => {
  const [m] = collect([': hello\nid: 7\nretry: 100\ndata: x\n\n']);
  assert.deepEqual(m, { event: 'message', data: 'x' });
});

test('blank lines without any fields do not produce phantom events', () => {
  assert.deepEqual(collect(['\n\n\n']), []);
});
