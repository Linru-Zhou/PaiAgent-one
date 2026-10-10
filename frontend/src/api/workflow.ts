import api from '../utils/request';
import { buildBackendUrl } from '../config/api';
import { clearStoredAuth, ensureValidAccessToken } from '../utils/auth';
import { SseParser } from '../utils/sseParser';
import { RecoveryTracker } from '../utils/executionRecovery';

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
 * 指定引擎同步执行（用于同一个工作流在 DAG / LangGraph 上对比运行）。
 * 服务端同步等到工作流跑完才返回，LLM 节点可能很慢，所以放宽默认 60s 的超时。
 */
export const executeWorkflowOnEngine = (
  id: number,
  inputData: string,
  engine: 'dag' | 'langgraph'
): Promise<ApiResult<ExecutionResponse>> => {
  return api.post(`/api/workflows/${id}/execute`, { inputData }, { params: { engine }, timeout: 300000 });
};

/**
 * 查询某次执行的状态与各节点进度（执行中也可查询，SSE 断线后靠它找回进度和结果）。
 */
export const getExecution = (id: number, executionId: number): Promise<ApiResult<ExecutionResponse>> => {
  return api.get(`/api/workflows/${id}/executions/${executionId}`);
};

/**
 * 把画布导出的流程图 PNG（data URL）连同流程 JSON 保存到后端的可见输出文件夹，返回保存路径。
 */
export const saveWorkflowImage = (
  id: number,
  png: string
): Promise<ApiResult<{ image: string; flowJson?: string }>> => {
  return api.post(`/api/workflows/${id}/export-image`, { png }, { timeout: 120000 });
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

export interface StreamOptions {
  /** 临时指定引擎："dag" / "langgraph"；不传则使用工作流自身配置的引擎 */
  engine?: 'dag' | 'langgraph';
  /** 断线后轮询的间隔与总时长上限 */
  recoverPollMs?: number;
  recoverMaxMs?: number;
}

export interface StreamHandle {
  /** 主动停止接收（不会中断服务端的执行） */
  close: () => void;
}

const STREAM_EVENT_NAMES = new Set([
  'WORKFLOW_START', 'NODE_START', 'NODE_SUCCESS', 'NODE_PROGRESS', 'NODE_ERROR', 'WORKFLOW_COMPLETE', 'ERROR',
]);

const toExecutionId = (data: unknown): number => {
  const value = typeof data === 'string' ? Number.parseInt(data, 10) : data;
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
};

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * 以 SSE 流式执行工作流。
 *
 * 用 fetch 读流而不是 EventSource：
 *  - 认证走 Authorization 头，token 不再出现在 URL / 日志 / 浏览器历史里；
 *  - 能拿到真实的 HTTP 状态码，401（登录过期）、429（服务繁忙）、断网分别处理，
 *    不再把"所有没收到数据的错误"都当成登录失效而踢回登录页；
 *  - 不会像 EventSource 那样自动重连——这个接口每次连接都会触发一次执行，自动重连等于重复执行。
 *
 * 流意外中断时（网络抖动、代理超时），如果已经拿到 executionId，就改为轮询
 * GET /executions/{id} 找回剩余进度和最终结果，并以和正常 SSE 相同的事件形式交给 onEvent。
 */
export const executeWorkflowStream = async (
  id: number,
  inputData: string,
  onEvent: (event: ExecutionEvent) => void,
  onComplete: () => void,
  onError: (error: Error) => void,
  options: StreamOptions = {}
): Promise<StreamHandle | null> => {
  const token = await ensureValidAccessToken();

  if (!token) {
    clearStoredAuth();
    window.location.href = '/login';
    onError(new Error('未登录'));
    return null;
  }

  const controller = new AbortController();
  const params = new URLSearchParams({ inputData });
  if (options.engine) {
    params.set('engine', options.engine);
  }
  const url = buildBackendUrl(`/api/workflows/${id}/execute/stream?${params.toString()}`);

  const run = async () => {
    let executionId = 0;
    let terminal = false;
    const tracker = new RecoveryTracker();

    const dispatch = (event: ExecutionEvent) => {
      tracker.observe(event);
      onEvent(event);
    };

    try {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      });

      if (response.status === 401) {
        clearStoredAuth();
        window.location.href = '/login';
        onError(new Error('登录已过期，请重新登录'));
        return;
      }
      if (response.status === 429) {
        onError(new Error('服务当前繁忙，请稍后重试'));
        return;
      }
      if (!response.ok || !response.body) {
        onError(new Error(`请求失败（HTTP ${response.status}）`));
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = new SseParser();

      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        for (const message of parser.push(decoder.decode(value, { stream: true }))) {
          if (!STREAM_EVENT_NAMES.has(message.event)) {
            continue;
          }
          let event: ExecutionEvent;
          try {
            event = JSON.parse(message.data) as ExecutionEvent;
          } catch {
            console.warn('忽略无法解析的 SSE 事件:', message);
            continue;
          }

          if (message.event === 'WORKFLOW_START') {
            executionId = toExecutionId(event.data);
          }
          dispatch(event);

          if (message.event === 'WORKFLOW_COMPLETE') {
            terminal = true;
            onComplete();
            return;
          }
          if (message.event === 'ERROR') {
            terminal = true;
            onError(new Error(event.message || '执行失败'));
            return;
          }
        }
      }
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      console.warn('SSE 连接异常，尝试按 executionId 恢复:', error);
    }

    if (terminal || controller.signal.aborted) {
      return;
    }

    // 流在没有收到结束事件时断了
    if (executionId <= 0) {
      onError(new Error('连接中断，且尚未拿到执行编号，无法恢复进度'));
      return;
    }

    const pollMs = options.recoverPollMs ?? 1500;
    const deadline = Date.now() + (options.recoverMaxMs ?? 10 * 60 * 1000);
    onEvent({ eventType: 'RECOVERY', message: `连接中断，正在按执行编号 #${executionId} 恢复进度...`, timestamp: Date.now() });

    while (!controller.signal.aborted && Date.now() < deadline) {
      await sleep(pollMs);
      try {
        const polled = await getExecution(id, executionId);
        if (polled.code !== 200 || !polled.data) {
          continue;
        }
        const { events, terminal: finished } = tracker.next(polled.data);
        events.forEach(dispatch);
        if (finished) {
          onComplete();
          return;
        }
      } catch (error) {
        console.warn('恢复轮询失败，稍后重试:', error);
      }
    }
    if (!controller.signal.aborted) {
      onError(new Error(`执行 #${executionId} 的恢复超时，请稍后在历史记录中查看结果`));
    }
  };

  void run();
  return { close: () => controller.abort() };
};
