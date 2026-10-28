import { createHash } from 'node:crypto';
import { ToolError } from './errors.ts';

export interface StartResult {
  executionId: number;
  status: string;
}

interface Entry {
  fingerprint: string;
  expiresAt: number;
  /** 提交成功后的结果；提交中时是进行中的 Promise */
  result: Promise<StartResult>;
  /** 上一次提交结果未知（超时）。此时不允许用同一个 request_id 再发 */
  unknown: ToolError | undefined;
}

const MAX_ENTRIES = 1_000;

/**
 * request_id 去重：Agent 在超时、网络抖动后常会“再试一次”，没有去重的话同一个任务会被提交两遍，
 * 而这里的每次执行都可能调用收费的大模型 / 生图 / 生视频接口。
 *
 * 范围说明（面试时要如实讲）：记录在本进程内存里，进程重启后失效，多个 MCP 服务实例之间也不共享。
 * 要做到真正的“恰好一次”，应该由 PaiAgent 后端按 request_id 做唯一键（目前后端没有）。
 */
export class IdempotencyStore {
  private readonly entries = new Map<string, Entry>();
  private readonly ttlMs: number;
  private readonly now: () => number;

  constructor(ttlMs: number, now: () => number = Date.now) {
    this.ttlMs = ttlMs;
    this.now = now;
  }

  static fingerprint(text: string): string {
    return createHash('sha256').update(text).digest('hex');
  }

  async run(
    workflowId: number,
    requestId: string,
    fingerprint: string,
    submit: () => Promise<StartResult>,
  ): Promise<{ result: StartResult; deduplicated: boolean }> {
    this.evictExpired();
    const key = `${workflowId}:${requestId}`;
    const existing = this.entries.get(key);

    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new ToolError('REQUEST_ID_REUSED', `request_id ${requestId} 已经用于另一份不同的输入`, {
          hint: '同一个 request_id 只能对应同一个任务。这是一个新任务，请换一个新的 request_id',
        });
      }
      if (existing.unknown) {
        throw existing.unknown;
      }
      return { result: await existing.result, deduplicated: true };
    }

    const entry: Entry = {
      fingerprint,
      expiresAt: this.now() + this.ttlMs,
      result: submit(),
      unknown: undefined,
    };
    this.entries.set(key, entry);
    try {
      return { result: await entry.result, deduplicated: false };
    } catch (error) {
      if (error instanceof ToolError && error.code === 'UNKNOWN_OUTCOME') {
        entry.unknown = error; // 保留记录：结果未知时，同一个 request_id 不能悄悄重发
      } else {
        this.entries.delete(key); // 明确失败（没处理过）可以用同一个 request_id 重试
      }
      throw error;
    }
  }

  size(): number {
    return this.entries.size;
  }

  private evictExpired(): void {
    const now = this.now();
    for (const [key, entry] of this.entries) {
      if (entry.expiresAt <= now) {
        this.entries.delete(key);
      }
    }
    while (this.entries.size >= MAX_ENTRIES) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) {
        break;
      }
      this.entries.delete(oldest);
    }
  }
}
