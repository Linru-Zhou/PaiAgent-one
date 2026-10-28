#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { PaiAgentClient } from './client.ts';
import { ConfigError, loadConfig } from './config.ts';
import { createServer, stderrLogger } from './server.ts';

async function main(): Promise<void> {
  const config = loadConfig();
  const server = createServer(config, new PaiAgentClient(config), stderrLogger);
  await server.connect(new StdioServerTransport());
  stderrLogger({
    event: 'started',
    base_url: config.baseUrl,
    auth: config.username ? 'username/password' : 'static token',
    allowed_workflows: config.allowedWorkflows ? [...config.allowedWorkflows] : 'all',
  });

  const shutdown = () => {
    void server.close().finally(() => process.exit(0));
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((error: unknown) => {
  // 配置错误直接告诉人怎么改；其他错误只打印消息，避免把堆栈里可能带的敏感信息写出去
  const message = error instanceof ConfigError ? error.message : error instanceof Error ? error.message : String(error);
  process.stderr.write(`paiagent-mcp 启动失败: ${message}\n`);
  process.exit(1);
});
