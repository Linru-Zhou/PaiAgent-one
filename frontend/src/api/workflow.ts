import api from '../utils/request';
import { buildBackendUrl } from '../config/api';
import { clearStoredAuth, ensureValidAccessToken } from '../utils/auth';

export interface NodeDefinition {
  id: number;
  nodeType: string;
  displayName: string;
  category: string;
  icon: string;
  inputSchema: string;
  outputSchema: string;
  configSchema: string;
}

export interface WorkflowData {
  name: string;
  description?: string;
  flowData: string;
  engineType?: string;
}

export interface Workflow {
  id: number;
  name: string;
  description: string;
  flowData: string;
  engineType?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface ExecutionNodeResult {
  nodeId: string;
  nodeName: string;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  input?: unknown;
  output?: unknown;
  duration?: number;
  error?: string;
  /** 机器可读的错误码，只有部分错误才有；据此决定交互，不要去解析 error 文案 */
  errorCode?: string;
  errorDetail?: Record<string, unknown>;
}

export interface ExecutionResponse {
  executionId: number;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  inputData?: unknown;
  nodeResults: ExecutionNodeResult[];
  outputData: unknown;
  duration: number;
  errorMessage?: string;
}

/** 与后端 VideoGenerateNodeExecutor 的 DecisionRequiredException.ERROR_CODE、ACTION_KEY 保持一致。 */
export const VIDEO_TASK_PENDING_CODE = 'VIDEO_TASK_PENDING';
export const VIDEO_TASK_ACTION_KEY = 'videoTaskAction';
export type VideoTaskAction = 'continue' | 'regenerate';

// 续跑是同步请求，视频节点最多会等 5 分钟（60 次 × 5 秒）；默认的 60 秒超时会在后端还在跑时就先报错
const RESUME_TIMEOUT_MS = 10 * 60 * 1000;

export interface ResumeExecutionRequest {
  startNodeId?: string;
  useSnapshotVariables?: boolean;
  modifiedVariables?: Record<string, unknown>;
  skipSuccessNodes?: boolean;
}

export interface ExecutionSnapshot {
  id: number;
  executionId: number;
  flowId: number;
  nodeId: string;
  nodeType: string;
  nodeName?: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  inputData?: Record<string, unknown>;
  outputData?: Record<string, unknown>;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  duration?: number;
  retryCount?: number;
  executionOrder?: number;
  createdAt?: string;
}

/**
 * 获取所有节点类型
 */
export const getNodeTypes = (): Promise<ApiResult<NodeDefinition[]>> => {
  return api.get('/api/node-types');
};

/**
 * 创建工作流
 */
export const createWorkflow = (data: WorkflowData): Promise<ApiResult<Workflow>> => {
  return api.post('/api/workflows', data);
};

/**
 * 获取工作流列表
 */
export const getWorkflows = (): Promise<ApiResult<Workflow[]>> => {
  return api.get('/api/workflows');
};

/**
 * 获取工作流详情
 */
export const getWorkflow = (id: number): Promise<ApiResult<Workflow>> => {
  return api.get(`/api/workflows/${id}`);
};

/**
 * 更新工作流
 */
export const updateWorkflow = (id: number, data: WorkflowData): Promise<ApiResult<Workflow>> => {
  return api.put(`/api/workflows/${id}`, data);
};

/**
 * 删除工作流
 */
export const deleteWorkflow = (id: number): Promise<ApiResult<void>> => {
  return api.delete(`/api/workflows/${id}`);
};

/**
 * 执行工作流
 */
export const executeWorkflow = (id: number, inputData: string): Promise<ApiResult<any>> => {
  return api.post(`/api/workflows/${id}/execute`, { inputData });
};

/**
 * 获取最近一次执行记录
 */
export const getLatestExecution = (id: number): Promise<ApiResult<ExecutionResponse | null>> => {
  return api.get(`/api/workflows/${id}/executions/latest`);
};

export const getExecutionSnapshots = (
  id: number,
  executionId: number
): Promise<ApiResult<ExecutionSnapshot[]>> => {
  return api.get(`/api/workflows/${id}/executions/${executionId}/snapshots`);
};

export const resumeWorkflowExecution = (
  id: number,
  executionId: number,
  request: ResumeExecutionRequest = {}
): Promise<ApiResult<ExecutionResponse>> => {
  return api.post(`/api/workflows/${id}/executions/${executionId}/resume`, {
    skipSuccessNodes: true,
    useSnapshotVariables: true,
    ...request,
  }, { timeout: RESUME_TIMEOUT_MS });
};

export interface ExecutionEvent {
  eventType: string;
  nodeId?: string;
  nodeName?: string;
  status?: string;
  message?: string;
  data?: any;
  timestamp?: number;
}

export const executeWorkflowStream = async (
  id: number, 
  inputData: string, 
  onEvent: (event: ExecutionEvent) => void,
  onComplete: () => void,
  onError: (error: Error) => void
) => {
  const token = await ensureValidAccessToken();
  
  if (!token) {
    clearStoredAuth();
    window.location.href = '/login';
    onError(new Error('未登录'));
    return null;
  }
  
  const url = buildBackendUrl(
    `/api/workflows/${id}/execute/stream?inputData=${encodeURIComponent(inputData)}&token=${token}`
  );
  
  const eventSource = new EventSource(url);
  
  let hasReceivedData = false;
  
  eventSource.addEventListener('WORKFLOW_START', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_START', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_SUCCESS', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_PROGRESS', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('NODE_ERROR', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
  });
  
  eventSource.addEventListener('WORKFLOW_COMPLETE', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
    eventSource.close();
    onComplete();
  });
  
  eventSource.addEventListener('ERROR', (e) => {
    hasReceivedData = true;
    const event = JSON.parse(e.data) as ExecutionEvent;
    onEvent(event);
    eventSource.close();
    onError(new Error(event.message || '执行失败'));
  });
  
  eventSource.onerror = () => {
    eventSource.close();
    
    if (!hasReceivedData) {
      clearStoredAuth();
      window.location.href = '/login';
      onError(new Error('认证失败,请重新登录'));
    } else {
      onError(new Error('连接中断'));
    }
  };
  
  return eventSource;
};
