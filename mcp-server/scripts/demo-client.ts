/**
 * 演示用的 MCP 客户端：把 PaiAgent-MCP 当成子进程拉起来，按“列出 → 提交 → 轮询”走一遍，
 * 并把每一次工具调用的名称、参数、结果打印出来（这就是“调用记录”）。
 *
 *   PAIAGENT_USERNAME=admin PAIAGENT_PASSWORD=admin123 \
 *     node scripts/demo-client.ts <工作流编号> "<输入文本>"
 *
 * 不传参数时只列出工作流。失败时会按 next_step 的提示再演示一次 resume_execution。
 * 末尾加 --cancel：提交后两秒取消它，再轮询到 CANCELLED（适合对着一个慢的工作流演示）。
 */
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const [workflowArg, ...inputParts] = process.argv.slice(2).filter((arg) => arg !== '--cancel');
const entry = fileURLToPath(new URL('../src/index.ts', import.meta.url));

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [entry],
  env: Object.fromEntries(Object.entries(process.env).filter(([name, value]) => name.startsWith('PAIAGENT_') && value !== undefined) as Array<[string, string]>),
  stderr: 'inherit',
});
const client = new Client({ name: 'paiagent-demo-client', version: '1' });
await client.connect(transport);

async function call(name: string, args: Record<string, unknown>): Promise<Record<string, any>> {
  console.log(`\n→ ${name} ${JSON.stringify(args)}`);
  const result = await client.callTool({ name, arguments: args });
  const text = (result.content as Array<{ text: string }>)[0]?.text ?? '';
  const data = JSON.parse(text) as Record<string, any>;
  console.log(`← ${result.isError ? '[isError] ' : ''}${JSON.stringify(data, null, 2)}`);
  return data;
}

try {
  const { tools } = await client.listTools();
  console.log(`服务端提供的工具: ${tools.map((tool) => tool.name).join(', ')}`);

  const listed = await call('list_workflows', {});
  if (workflowArg) {
    const workflowId = Number(workflowArg);
    const started = await call('start_workflow', { workflow_id: workflowId, input_text: inputParts.join(' ') || '你好', request_id: `demo-${Date.now()}` });
    if (started.ok && process.argv.includes('--cancel')) {
      // 演示取消：等两秒让节点跑起来，再取消，然后轮询到 CANCELLED
      await new Promise((resolve) => setTimeout(resolve, 2000));
      await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      await call('cancel_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      let view = await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      for (let i = 0; i < 30 && !view.done; i++) {
        await new Promise((resolve) => setTimeout(resolve, (view.retry_after_seconds ?? 1) * 1000));
        view = await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      }
      await call('cancel_execution', { workflow_id: workflowId, execution_id: started.execution_id }); // 再取消一次：幂等
    } else if (started.ok) {
      let view = await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      for (let i = 0; i < 60 && !view.done; i++) {
        await new Promise((resolve) => setTimeout(resolve, (view.retry_after_seconds ?? 4) * 1000));
        view = await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      }
      if (view.status === 'FAILED' && !view.needs_decision) {
        await call('get_execution', { workflow_id: workflowId, execution_id: started.execution_id, detail: 'nodes' });
        await call('resume_execution', { workflow_id: workflowId, execution_id: started.execution_id });
      }
    }
  } else {
    console.log(`\n共 ${listed.total} 个工作流。再传 <工作流编号> "<输入文本>" 可以提交一次执行。`);
  }
} finally {
  await client.close();
}
