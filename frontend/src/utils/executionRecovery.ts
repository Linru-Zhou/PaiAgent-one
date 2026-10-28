/**
 * SSE 断线后的恢复。
 *
 * 后端在浏览器断开后不会中断执行（结果照常落库），所以断线后用 executionId 轮询
 * GET /executions/{id} 就能拿回进度和最终结果。这里把"轮询到的状态"与"断线前已经收到的实时事件"做差，
 * 只补发缺失的事件，让界面沿用和正常 SSE 完全相同的处理路径。
 */
export interface RecoveryEvent {
  eventType: string;
  nodeId?: string;
  nodeName?: string;
  status?: string;
  message?: string;
  data?: unknown;
  timestamp?: number;
}

export interface PolledNode {
  nodeId: string;
  nodeName?: string;
  status: string;
  input?: unknown;
  output?: unknown;
  duration?: number;
  error?: string;
}

export interface PolledExecution {
  executionId?: number;
  status: string;
  nodeResults?: PolledNode[];
  outputData?: unknown;
  duration?: number;
  errorMessage?: string;
}

const parseIfJson = (value: unknown): unknown => {
  if (typeof value !== 'string') {
    return value;
  }
  const trimmed = value.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return value;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
};

export class RecoveryTracker {
  private started = new Set<string>();
  private finished = new Set<string>();

  /** 把断线前收到的每个实时事件登记进来，避免恢复时重复补发。 */
  observe(event: RecoveryEvent): void {
    if (!event.nodeId) {
      return;
    }
    if (event.eventType === 'NODE_START') {
      this.started.add(event.nodeId);
    } else if (event.eventType === 'NODE_SUCCESS' || event.eventType === 'NODE_ERROR') {
      this.started.add(event.nodeId);
      this.finished.add(event.nodeId);
    }
  }

  /** 处理一次轮询结果：返回需要补发的事件，以及整次执行是否已经结束。 */
  next(poll: PolledExecution): { events: RecoveryEvent[]; terminal: boolean } {
    const events: RecoveryEvent[] = [];
    const now = Date.now();

    for (const node of poll.nodeResults ?? []) {
      const name = node.nodeName || node.nodeId;
      if (!this.started.has(node.nodeId)) {
        this.started.add(node.nodeId);
        events.push({ eventType: 'NODE_START', nodeId: node.nodeId, nodeName: name, status: 'RUNNING', timestamp: now });
      }
      if (this.finished.has(node.nodeId)) {
        continue;
      }
      if (node.status === 'SUCCESS') {
        this.finished.add(node.nodeId);
        events.push({
          eventType: 'NODE_SUCCESS',
          nodeId: node.nodeId,
          nodeName: name,
          status: 'SUCCESS',
          message: `节点执行成功，耗时 ${node.duration ?? 0}ms`,
          data: { input: parseIfJson(node.input), output: parseIfJson(node.output) },
          timestamp: now,
        });
      } else if (node.status === 'FAILED' || node.status === 'CANCELLED') {
        // 被取消的节点和出错的节点走同一条事件，由界面按 error 文案区分（后端取消时的文案固定为"执行已取消"）
        this.finished.add(node.nodeId);
        events.push({
          eventType: 'NODE_ERROR',
          nodeId: node.nodeId,
          nodeName: name,
          status: node.status,
          message: node.error || (node.status === 'CANCELLED' ? '执行已取消' : '节点执行失败'),
          timestamp: now,
        });
      }
    }

    // CANCELLING 是"取消已受理、节点正在退出"，还没有结束，要继续轮询到 CANCELLED
    const terminal = poll.status !== 'RUNNING' && poll.status !== 'CANCELLING';
    if (terminal) {
      events.push({
        eventType: 'WORKFLOW_COMPLETE',
        status: poll.status,
        message: poll.status === 'SUCCESS'
          ? `工作流执行成功，总耗时 ${poll.duration ?? 0}ms`
          : (poll.errorMessage || (poll.status === 'CANCELLED' ? `工作流已取消，总耗时 ${poll.duration ?? 0}ms` : `工作流执行失败，总耗时 ${poll.duration ?? 0}ms`)),
        data: parseIfJson(poll.outputData),
        timestamp: now,
      });
    }
    return { events, terminal };
  }
}
