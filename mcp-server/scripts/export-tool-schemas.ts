/**
 * 打印同一个工具在三种接口里的样子：MCP（tools/list）、OpenAI 函数调用、Anthropic 工具。
 *
 *   node scripts/export-tool-schemas.ts                  # 默认展示 start_workflow
 *   node scripts/export-tool-schemas.ts get_execution    # 指定工具
 *   node scripts/export-tool-schemas.ts --all            # 把全部工具导出成 OpenAI 的 tools 数组（可直接粘进请求）
 *
 * 不需要后端在运行：只读取工具的定义，不会调用任何工具。
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { PaiAgentClient } from '../src/client.ts';
import { loadConfig } from '../src/config.ts';
import { toAnthropicTool, toMcpTool, toOpenAiTool } from '../src/exportSchemas.ts';
import { createServer } from '../src/server.ts';

const config = loadConfig({ PAIAGENT_TOKEN: 'only-reads-tool-definitions' });
const server = createServer(config, new PaiAgentClient(config));
const client = new Client({ name: 'schema-exporter', version: '1' });
const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
await Promise.all([server.connect(serverSide), client.connect(clientSide)]);
const { tools } = await client.listTools();

const arg = process.argv[2] ?? 'start_workflow';
if (arg === '--all') {
  console.log(JSON.stringify(tools.map(toOpenAiTool), null, 2));
} else {
  const tool = tools.find((candidate) => candidate.name === arg);
  if (!tool) {
    console.error(`没有这个工具: ${arg}。可用: ${tools.map((candidate) => candidate.name).join(', ')}`);
    process.exitCode = 1;
  } else {
    console.log('===== 1. MCP（客户端通过 tools/list 拿到的） =====');
    console.log(JSON.stringify(toMcpTool(tool), null, 2));
    console.log('\n===== 2. OpenAI 函数调用（请求里的 tools[] 的一项） =====');
    console.log(JSON.stringify(toOpenAiTool(tool), null, 2));
    console.log('\n===== 3. Anthropic Messages API（请求里的 tools[] 的一项） =====');
    console.log(JSON.stringify(toAnthropicTool(tool), null, 2));
  }
}
await client.close();
await server.close();
