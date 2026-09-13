import api from '../utils/request';

export interface McpToolConfig {
  id: number;
  name: string;
  description?: string;
  toolType: string;
  toolName: string;
  transport: string;
  command: string;
  args: string[];
  env: Record<string, string>;
  enabled: number;
  preset: number;
  createdAt?: string;
  updatedAt?: string;
}

interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export const getMcpTools = (): Promise<ApiResult<McpToolConfig[]>> =>
  api.get('/api/mcp-tools');

export const createAgentPlanWebSearchMcp = (data: {
  name?: string;
  description?: string;
  apiKey: string;
}): Promise<ApiResult<McpToolConfig>> =>
  api.post('/api/mcp-tools/agent-plan-web-search', data);

export interface CustomMcpToolPayload {
  name: string;
  description?: string;
  toolType?: string;
  toolName: string;
  transport?: string;
  command: string;
  args?: string[];
  env?: Record<string, string>;
  enabled?: number;
}

export const createMcpTool = (data: CustomMcpToolPayload): Promise<ApiResult<McpToolConfig>> =>
  api.post('/api/mcp-tools', data);

export const deleteMcpTool = (id: number): Promise<ApiResult<void>> =>
  api.delete(`/api/mcp-tools/${id}`);

export const testMcpTool = (
  id: number,
  data: { query?: string }
): Promise<ApiResult<Record<string, unknown>>> =>
  api.post(`/api/mcp-tools/${id}/test`, data);
