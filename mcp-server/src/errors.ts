import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';

/**
 * 给模型看的错误码。每个错误都回答三件事：发生了什么（message）、能不能重试（retryable）、下一步做什么（hint）。
 * 模型据此决定“等一会儿再试”“告诉用户”还是“换个办法”，而不是去猜一段随时会变的文案。
 */
export type ErrorCode =
  | 'BUSY' // 后端 429：执行队列满了
  | 'CONFLICT' // 后端 409：这次执行仍在运行，或已被别人续跑
  | 'NOT_FOUND'
  | 'FORBIDDEN' // 不在 PAIAGENT_ALLOWED_WORKFLOWS 里
  | 'AUTH_FAILED' // 登录失败或令牌无效，重试没用
  | 'INVALID_INPUT'
  | 'NEEDS_DECISION' // 需要用户做选择（视频任务继续等待/重新生成）
  | 'UPSTREAM_UNAVAILABLE' // 连不上或超时，请求肯定没有被处理
  | 'UNKNOWN_OUTCOME' // 超时但请求可能已被处理（提交类操作），不能盲目重发
  | 'REQUEST_ID_REUSED' // 同一个 request_id 对应了不同的输入
  | 'UPSTREAM_ERROR' // 后端返回了业务错误
  | 'INTERNAL';

export interface ToolErrorOptions {
  hint?: string;
  retryable?: boolean;
  retryAfterSeconds?: number;
  details?: Record<string, unknown>;
}

export class ToolError extends Error {
  readonly code: ErrorCode;
  readonly hint: string | undefined;
  readonly retryable: boolean;
  readonly retryAfterSeconds: number | undefined;
  readonly details: Record<string, unknown> | undefined;

  constructor(code: ErrorCode, message: string, options: ToolErrorOptions = {}) {
    super(message);
    this.name = 'ToolError';
    this.code = code;
    this.hint = options.hint;
    this.retryable = options.retryable ?? false;
    this.retryAfterSeconds = options.retryAfterSeconds;
    this.details = options.details;
  }

  toPayload(): Record<string, unknown> {
    return {
      ok: false,
      code: this.code,
      message: this.message,
      ...(this.hint ? { hint: this.hint } : {}),
      retryable: this.retryable,
      ...(this.retryAfterSeconds !== undefined ? { retry_after_seconds: this.retryAfterSeconds } : {}),
      ...(this.details ? { details: this.details } : {}),
    };
  }
}

export function success(payload: Record<string, unknown>): CallToolResult {
  return {
    content: [{ type: 'text', text: JSON.stringify(payload) }],
    structuredContent: payload,
  };
}

export function failure(error: ToolError): CallToolResult {
  const payload = error.toPayload();
  return {
    isError: true,
    content: [{ type: 'text', text: JSON.stringify(payload) }],
    structuredContent: payload,
  };
}
