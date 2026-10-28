import type { Config } from './config.ts';
import { ToolError } from './errors.ts';

// 只声明工具层用到的字段。后端的 DTO 在 backend/src/main/java/com/paiagent/dto/。
export interface WorkflowDto {
  id: number;
  name: string;
  description?: string | null;
  flowData?: string | null;
  engineType?: string | null;
}

export interface NodeResultDto {
  nodeId: string;
  nodeName?: string | null;
  status: string;
  output?: string | null;
  duration?: number | null;
  error?: string | null;
  errorCode?: string | null;
  errorDetail?: Record<string, unknown> | null;
}

export interface ExecutionDto {
  executionId: number;
  status: string;
  outputData?: string | null;
  duration?: number | null;
  errorMessage?: string | null;
  nodeResults?: NodeResultDto[] | null;
}

export interface CancelOutcomeDto {
  executionId: number;
  /** CANCELLING：已受理，节点正在退出；CANCELLED：已经取消 */
  status: string;
  message?: string | null;
}

export interface StartedExecution {
  executionId: number;
  status: string;
}

/** 后端所有接口都包一层 {code, message, data}；业务错误（429/409/500…）在 code 里，HTTP 状态通常还是 200。 */
interface Envelope<T> {
  code?: number;
  message?: string;
  data?: T;
}

type FetchLike = typeof fetch;

interface RequestOptions {
  body?: unknown;
  timeoutMs?: number;
  /** 提交类请求：超时说明“不知道有没有被处理”，要报 UNKNOWN_OUTCOME 而不是可重试的错误 */
  submitting?: boolean;
}

/** 请求根本没有发出去的网络错误码（可以放心地说“没处理过”）。 */
const NOT_SENT_CODES = new Set(['ECONNREFUSED', 'ENOTFOUND', 'EHOSTUNREACH', 'ENETUNREACH', 'EAI_AGAIN']);

export class PaiAgentClient {
  private readonly config: Config;
  private readonly fetchImpl: FetchLike;
  private token: string | undefined;
  private loginInFlight: Promise<string> | undefined;

  constructor(config: Config, fetchImpl: FetchLike = fetch) {
    this.config = config;
    this.fetchImpl = fetchImpl;
    this.token = config.token;
  }

  listWorkflows(): Promise<WorkflowDto[]> {
    return this.request<WorkflowDto[]>('GET', '/api/workflows');
  }

  /** 异步提交：后端等到拿到 executionId 就返回（最多等 10 秒），执行本身在后台继续。 */
  startExecution(workflowId: number, inputData: string): Promise<StartedExecution> {
    return this.request<StartedExecution>('POST', `/api/workflows/${workflowId}/execute/async`, {
      body: { inputData },
      timeoutMs: this.config.submitTimeoutMs,
      submitting: true,
    });
  }

  /**
   * 取消：后端是协作式取消，立即返回 CANCELLING（已受理，节点正在退出）或 CANCELLED；
   * 重复取消是幂等的。已经结束（SUCCESS / FAILED）的执行后端返回业务码 409。
   */
  cancelExecution(workflowId: number, executionId: number): Promise<CancelOutcomeDto> {
    return this.request<CancelOutcomeDto>('POST', `/api/workflows/${workflowId}/executions/${executionId}/cancel`);
  }

  getExecution(workflowId: number, executionId: number): Promise<ExecutionDto> {
    return this.request<ExecutionDto>('GET', `/api/workflows/${workflowId}/executions/${executionId}`);
  }

  /**
   * 续跑接口是同步的：后端会一直执行到结束才返回，视频节点可能要几分钟。
   * 这里最多等 waitMs，等不到就按“已受理、仍在运行”处理，让调用方改用 getExecution 轮询
   * （客户端断开不会中断后端的执行，见 AGENTS.md 里 SSE 一节）。
   */
  async resumeExecution(
    workflowId: number,
    executionId: number,
    body: Record<string, unknown>,
    waitMs: number,
  ): Promise<ExecutionDto | 'STILL_RUNNING'> {
    try {
      return await this.request<ExecutionDto>('POST', `/api/workflows/${workflowId}/executions/${executionId}/resume`, {
        body,
        timeoutMs: waitMs,
        submitting: true,
      });
    } catch (error) {
      if (error instanceof ToolError && error.code === 'UNKNOWN_OUTCOME') {
        return 'STILL_RUNNING';
      }
      throw error;
    }
  }

  // ------------------------------------------------------------------ http

  private async request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await this.ensureToken();
      let response: Response;
      try {
        response = await this.fetchImpl(this.config.baseUrl + path, {
          method,
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
          signal: AbortSignal.timeout(options.timeoutMs ?? this.config.requestTimeoutMs),
        });
      } catch (error) {
        throw this.networkError(error, options);
      }

      if (response.status === 401) {
        // 401 由后端的拦截器在进入业务代码之前返回，所以重新登录后重发是安全的，即使是提交类请求
        if (attempt === 0 && this.canLogin()) {
          this.token = undefined;
          continue;
        }
        throw this.authFailed('PaiAgent 拒绝了当前令牌（可能已过期）');
      }
      return this.unwrap<T>(response);
    }
    throw new ToolError('INTERNAL', '请求重试后仍未完成');
  }

  private async unwrap<T>(response: Response): Promise<T> {
    const text = await response.text();
    let body: Envelope<T> | undefined;
    try {
      const parsed: unknown = JSON.parse(text);
      body = typeof parsed === 'object' && parsed !== null ? (parsed as Envelope<T>) : undefined;
    } catch {
      body = undefined;
    }
    if (!body) {
      throw new ToolError('UPSTREAM_UNAVAILABLE', `PaiAgent 返回了无法解析的响应（HTTP ${response.status}）`, {
        hint: '服务可能正在重启或被网关拦截，稍后重试',
        retryable: response.status >= 500,
        retryAfterSeconds: 10,
      });
    }
    const code = body.code ?? response.status;
    if (code === 200 && response.ok) {
      return body.data as T;
    }
    throw this.businessError(code, body.message ?? `HTTP ${response.status}`);
  }

  private businessError(code: number, message: string): ToolError {
    if (code === 429) {
      return new ToolError('BUSY', message, {
        hint: '服务繁忙（执行队列已满）。等待 retry_after_seconds 后重试，不要并发提交',
        retryable: true,
        retryAfterSeconds: 10,
      });
    }
    if (code === 409) {
      return new ToolError('CONFLICT', message, {
        hint: '这次执行仍在运行，或已被别人续跑。改用 get_execution 查看状态，不要重复提交',
      });
    }
    if (code === 401) {
      return this.authFailed(message);
    }
    // 后端目前对“不存在”也返回 500，只能靠文案识别；后端若改成返回 404 就不需要这个判断了
    if (message.includes('不存在')) {
      return new ToolError('NOT_FOUND', message, {
        hint: '编号不对。工作流编号用 list_workflows 获取；execution_id 来自 start_workflow 的返回',
      });
    }
    return new ToolError('UPSTREAM_ERROR', message, {
      hint: '后端报告了业务错误，把 message 原样告诉用户，不要自动重试',
    });
  }

  private networkError(error: unknown, options: RequestOptions): ToolError {
    const name = error instanceof Error ? error.name : '';
    const code = (error instanceof Error && typeof error.cause === 'object' && error.cause !== null
      ? (error.cause as { code?: string }).code
      : undefined) ?? '';
    const timedOut = name === 'TimeoutError' || name === 'AbortError';
    const notSent = NOT_SENT_CODES.has(code);

    if (options.submitting && !notSent) {
      return new ToolError('UNKNOWN_OUTCOME', timedOut ? '提交请求超时，无法确定 PaiAgent 是否已经开始执行' : `提交请求中断（${code || name}），无法确定 PaiAgent 是否已经开始执行`, {
        hint: '不要直接重发，否则可能重复执行（重复计费）。先用 get_execution 查看是否已有新的执行，确认没有再换一个新的 request_id 重试',
      });
    }
    return new ToolError('UPSTREAM_UNAVAILABLE', timedOut ? 'PaiAgent 响应超时' : `连不上 PaiAgent（${code || name}）`, {
      hint: '请求没有被处理。稍后重试；一直失败就告诉用户检查 PaiAgent 后端是否在运行',
      retryable: true,
      retryAfterSeconds: 10,
    });
  }

  // ------------------------------------------------------------------ auth

  private canLogin(): boolean {
    return Boolean(this.config.username && this.config.password);
  }

  private authFailed(message: string): ToolError {
    return new ToolError('AUTH_FAILED', message, {
      hint: '服务端的凭据有问题，需要人去检查 PAIAGENT_USERNAME / PAIAGENT_PASSWORD（或令牌）。重试没有用，请告诉用户',
    });
  }

  private async ensureToken(): Promise<string> {
    if (this.token) {
      return this.token;
    }
    if (!this.canLogin()) {
      throw this.authFailed('没有可用的令牌，且未配置账号密码');
    }
    // 多个并发请求同时发现令牌失效时，只登录一次
    this.loginInFlight ??= this.login().finally(() => {
      this.loginInFlight = undefined;
    });
    this.token = await this.loginInFlight;
    return this.token;
  }

  private async login(): Promise<string> {
    let response: Response;
    try {
      response = await this.fetchImpl(`${this.config.baseUrl}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ username: this.config.username, password: this.config.password }),
        signal: AbortSignal.timeout(this.config.requestTimeoutMs),
      });
    } catch (error) {
      throw this.networkError(error, {});
    }
    const text = await response.text();
    let body: Envelope<{ token?: string }> | undefined;
    try {
      body = JSON.parse(text) as Envelope<{ token?: string }>;
    } catch {
      body = undefined;
    }
    const token = body?.data?.token;
    if (body?.code !== 200 || !token) {
      throw this.authFailed(`登录 PaiAgent 失败: ${body?.message ?? `HTTP ${response.status}`}`);
    }
    return token;
  }
}
