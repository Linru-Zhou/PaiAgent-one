/**
 * 配置全部来自环境变量。密码和令牌只在这里读取，不写进日志、不出现在任何工具返回里。
 */
export class ConfigError extends Error {}

export interface Config {
  /** PaiAgent 后端地址，不带结尾的斜杠 */
  baseUrl: string;
  username: string | undefined;
  password: string | undefined;
  /** 直接给一个现成的令牌（过期后无法自动续，所以更推荐账号密码） */
  token: string | undefined;
  requestTimeoutMs: number;
  /** 提交类请求（start / resume）的超时。后端异步提交最多会等 10 秒拿 executionId，所以默认比普通请求长 */
  submitTimeoutMs: number;
  /** resume_execution 最多等多久：后端的续跑接口是同步的，等不到就让模型改用 get_execution 轮询 */
  resumeWaitMs: number;
  /** start_workflow 的 input_text 上限（字符数） */
  maxInputChars: number;
  /** 工具返回给模型的最终输出上限（字符数），超出部分截断并标注 */
  maxOutputChars: number;
  /** 非空时只允许操作这些工作流（最小权限）；null 表示不限制 */
  allowedWorkflows: ReadonlySet<number> | null;
  /** request_id 去重记录的保留时间 */
  idempotencyTtlMs: number;
}

function intFrom(env: NodeJS.ProcessEnv, name: string, fallback: number, min: number, max: number): number {
  const raw = env[name]?.trim();
  if (!raw) {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new ConfigError(`${name} 必须是 ${min}~${max} 之间的整数，收到: ${raw}`);
  }
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const baseUrl = (env.PAIAGENT_BASE_URL?.trim() || 'http://localhost:8084').replace(/\/+$/, '');
  try {
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error('protocol');
    }
  } catch {
    throw new ConfigError(`PAIAGENT_BASE_URL 不是合法的 http(s) 地址: ${baseUrl}`);
  }

  const username = env.PAIAGENT_USERNAME?.trim() || undefined;
  const password = env.PAIAGENT_PASSWORD || undefined;
  const token = env.PAIAGENT_TOKEN?.trim() || undefined;
  if (!token && !(username && password)) {
    throw new ConfigError('缺少凭据：请设置 PAIAGENT_USERNAME 和 PAIAGENT_PASSWORD（推荐，令牌过期后会自动重新登录），或者设置 PAIAGENT_TOKEN');
  }

  let allowedWorkflows: Set<number> | null = null;
  const allowedRaw = env.PAIAGENT_ALLOWED_WORKFLOWS?.trim();
  if (allowedRaw) {
    allowedWorkflows = new Set();
    for (const part of allowedRaw.split(',')) {
      const id = Number(part.trim());
      if (!Number.isInteger(id) || id <= 0) {
        throw new ConfigError(`PAIAGENT_ALLOWED_WORKFLOWS 应是逗号分隔的工作流编号，收到: ${allowedRaw}`);
      }
      allowedWorkflows.add(id);
    }
  }

  return {
    baseUrl,
    username,
    password,
    token,
    requestTimeoutMs: intFrom(env, 'PAIAGENT_REQUEST_TIMEOUT_MS', 15_000, 1_000, 120_000),
    submitTimeoutMs: intFrom(env, 'PAIAGENT_SUBMIT_TIMEOUT_MS', 20_000, 1_000, 120_000),
    resumeWaitMs: intFrom(env, 'PAIAGENT_RESUME_WAIT_MS', 8_000, 500, 120_000),
    maxInputChars: intFrom(env, 'PAIAGENT_MAX_INPUT_CHARS', 2_000, 1, 20_000),
    maxOutputChars: intFrom(env, 'PAIAGENT_MAX_OUTPUT_CHARS', 4_000, 200, 100_000),
    allowedWorkflows,
    idempotencyTtlMs: intFrom(env, 'PAIAGENT_IDEMPOTENCY_TTL_SECONDS', 3_600, 10, 86_400) * 1_000,
  };
}
