import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { PaiAgentClient } from '../src/client.ts';
import { toAnthropicTool, toMcpTool, toOpenAiTool } from '../src/exportSchemas.ts';
import { createServer } from '../src/server.ts';
import { testConfig } from './helpers.ts';

async function listTools() {
  const config = testConfig('http://127.0.0.1:1', { token: 'unused', username: undefined, password: undefined });
  const server = createServer(config, new PaiAgentClient(config));
  const client = new Client({ name: 'schema-test', version: '1' });
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverSide), client.connect(clientSide)]);
  const { tools } = await client.listTools();
  await client.close();
  await server.close();
  return tools;
}

test('同一份定义转成 OpenAI / Anthropic 格式：名称、描述、参数一致，没有 $schema 元信息', async () => {
  const tools = await listTools();
  assert.equal(tools.length, 5);

  for (const tool of tools) {
    const mcp = toMcpTool(tool);
    const openai = toOpenAiTool(tool);
    const anthropic = toAnthropicTool(tool);

    assert.equal(openai.type, 'function');
    assert.equal(openai.function.name, mcp.name);
    assert.equal(anthropic.name, mcp.name);
    assert.equal(openai.function.description, mcp.description, `${tool.name} 的描述在 OpenAI 格式里被改动了`);
    assert.equal(anthropic.description, mcp.description);
    assert.deepEqual(Object.keys(openai.function.parameters).includes('$schema'), false);
    assert.deepEqual(anthropic.input_schema, openai.function.parameters, '两种格式里的参数 schema 应该是同一份');
    assert.equal(openai.function.parameters.type, 'object');
  }
});

test('start_workflow 的必填参数和约束在 OpenAI 格式里完整保留', async () => {
  const tools = await listTools();
  const start = toOpenAiTool(tools.find((tool) => tool.name === 'start_workflow')!);
  const parameters = start.function.parameters as {
    required: string[];
    properties: Record<string, { type: string; maxLength?: number; pattern?: string }>;
  };

  assert.deepEqual([...parameters.required].sort(), ['input_text', 'workflow_id']);
  assert.equal(parameters.properties.workflow_id?.type, 'integer');
  assert.equal(parameters.properties.input_text?.maxLength, 2000);
  assert.ok(parameters.properties.request_id?.pattern, 'request_id 的字符集约束不能丢');
});
