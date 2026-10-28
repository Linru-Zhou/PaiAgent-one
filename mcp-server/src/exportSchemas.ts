/**
 * 同一份工具定义，输出成不同 Agent 接口认得的格式。
 *
 * 工具只在 server.ts 里定义一次（名称、描述、zod 参数）。MCP 客户端通过 tools/list 拿到的是第一种；
 * 想让不走 MCP 的调用方（直接用 OpenAI / Anthropic 的函数调用接口的程序）也能用，可以把同一份定义转成它们的格式，
 * 调用时再把模型给出的函数名和参数转发到这个服务（或直接转发到 PaiAgent 的 REST 接口）。
 * 三种格式只是外壳不同，名称、描述、参数 JSON Schema 是同一份，所以不会出现"两边描述不一致"。
 */
import type { Tool } from '@modelcontextprotocol/sdk/types.js';

/** MCP 的 tools/list 条目就是 {name, description, inputSchema, annotations}，原样返回。 */
export function toMcpTool(tool: Tool): Tool {
  return tool;
}

/** OpenAI Chat Completions 的 tools 参数：{type:'function', function:{name, description, parameters}} */
export function toOpenAiTool(tool: Tool): { type: 'function'; function: { name: string; description: string; parameters: Record<string, unknown> } } {
  return {
    type: 'function',
    function: {
      name: tool.name,
      description: tool.description ?? '',
      parameters: withoutMetaKeys(tool.inputSchema as Record<string, unknown>),
    },
  };
}

/** Anthropic Messages API 的 tools 参数：{name, description, input_schema} */
export function toAnthropicTool(tool: Tool): { name: string; description: string; input_schema: Record<string, unknown> } {
  return {
    name: tool.name,
    description: tool.description ?? '',
    input_schema: withoutMetaKeys(tool.inputSchema as Record<string, unknown>),
  };
}

/** JSON Schema 顶层的 $schema 是给校验器看的元信息，函数调用接口不需要，去掉让请求更干净。 */
function withoutMetaKeys(schema: Record<string, unknown>): Record<string, unknown> {
  const { $schema: _ignored, ...rest } = schema;
  return rest;
}
