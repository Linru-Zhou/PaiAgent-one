/**
 * 同一个工作流在两个引擎（DAG / LangGraph）上跑完后，逐节点对比结果。
 *
 * 判定口径：
 *  - "一致"看的是确定性的部分：执行了哪些节点、每个节点的状态；
 *  - 输出内容是否相同单独列出。LLM 节点即使同一个引擎跑两次输出也不一样，
 *    所以输出不同只作提示，不算引擎不一致。
 */
export interface DiffNodeResult {
  nodeId: string;
  nodeName?: string;
  status: string;
  output?: unknown;
  duration?: number;
  error?: string;
}

export interface DiffExecution {
  status: string;
  duration?: number;
  nodeResults: DiffNodeResult[];
  outputData?: unknown;
}

export type RowVerdict = 'same' | 'output-differs' | 'status-differs' | 'only-in-a' | 'only-in-b';

export interface DiffRow {
  nodeId: string;
  nodeName: string;
  a?: DiffNodeResult;
  b?: DiffNodeResult;
  verdict: RowVerdict;
}

export interface DiffReport {
  rows: DiffRow[];
  /** 节点集合相同且每个节点状态相同 */
  consistent: boolean;
  /** 所有节点输出（含最终输出）逐字相同 */
  outputsIdentical: boolean;
  statusA: string;
  statusB: string;
  durationA?: number;
  durationB?: number;
}

/** 运行时才有的、每次执行都会变的字段，对比前剔除。 */
const VOLATILE_KEYS = new Set(['__executionId__', '__idempotencyKey__', '__nodeOutputs__']);

/** 键排序后序列化，保证对象键顺序不同也能判等。 */
export const stableStringify = (value: unknown): string => {
  const normalize = (v: unknown): unknown => {
    if (Array.isArray(v)) {
      return v.map(normalize);
    }
    if (v && typeof v === 'object') {
      return Object.keys(v as Record<string, unknown>)
        .filter((k) => !VOLATILE_KEYS.has(k))
        .sort()
        .reduce<Record<string, unknown>>((acc, k) => {
          acc[k] = normalize((v as Record<string, unknown>)[k]);
          return acc;
        }, {});
    }
    return v;
  };
  return JSON.stringify(normalize(value));
};

export const diffExecutions = (a: DiffExecution, b: DiffExecution): DiffReport => {
  const byIdA = new Map(a.nodeResults.map((n) => [n.nodeId, n]));
  const byIdB = new Map(b.nodeResults.map((n) => [n.nodeId, n]));
  const orderedIds = [...a.nodeResults.map((n) => n.nodeId)];
  for (const n of b.nodeResults) {
    if (!byIdA.has(n.nodeId)) {
      orderedIds.push(n.nodeId);
    }
  }

  const rows: DiffRow[] = orderedIds.map((nodeId) => {
    const na = byIdA.get(nodeId);
    const nb = byIdB.get(nodeId);
    let verdict: RowVerdict;
    if (na && !nb) {
      verdict = 'only-in-a';
    } else if (!na && nb) {
      verdict = 'only-in-b';
    } else if (na!.status !== nb!.status) {
      verdict = 'status-differs';
    } else if (stableStringify(na!.output) !== stableStringify(nb!.output)) {
      verdict = 'output-differs';
    } else {
      verdict = 'same';
    }
    return { nodeId, nodeName: na?.nodeName || nb?.nodeName || nodeId, a: na, b: nb, verdict };
  });

  const consistent = rows.every((r) => r.verdict === 'same' || r.verdict === 'output-differs');
  const outputsIdentical =
    rows.every((r) => r.verdict === 'same') && stableStringify(a.outputData) === stableStringify(b.outputData);

  return {
    rows,
    consistent,
    outputsIdentical,
    statusA: a.status,
    statusB: b.status,
    durationA: a.duration,
    durationB: b.duration,
  };
};

/** 后端非流式接口返回的 input / output 是 JSON 字符串，对比前先还原成对象，否则只能做字符串比较。 */
export const toDiffExecution = (response: {
  status: string;
  duration?: number;
  outputData?: unknown;
  nodeResults?: Array<{ nodeId: string; nodeName?: string; status: string; output?: unknown; duration?: number; error?: string }>;
}): DiffExecution => {
  const parse = (value: unknown): unknown => {
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
  return {
    status: response.status,
    duration: response.duration,
    outputData: parse(response.outputData),
    nodeResults: (response.nodeResults ?? []).map((n) => ({ ...n, output: parse(n.output) })),
  };
};
