/**
 * 把执行快照整理成时间线（甘特图）模型：每个节点一条横条，横轴是相对开始时间。
 * 同一时间段里有几条横条重叠，就说明当时有几个节点在并行执行。
 */
export interface TimelineSnapshot {
  nodeId: string;
  nodeName?: string;
  nodeType?: string;
  status: string;
  startedAt?: string | number[] | null;
  completedAt?: string | number[] | null;
  duration?: number | null;
  executionOrder?: number | null;
}

export interface TimelineBar {
  nodeId: string;
  label: string;
  status: string;
  startMs: number;
  endMs: number;
  durationMs: number;
}

export interface TimelineModel {
  bars: TimelineBar[];
  /** 没有起止时间的节点（被条件分支跳过 / 还没轮到） */
  notRun: Array<{ nodeId: string; label: string; status: string }>;
  /** 墙钟总时长：最晚结束 - 最早开始 */
  wallMs: number;
  /** 各节点耗时之和，也就是"全部串行执行"需要的时间 */
  sumMs: number;
  /** sumMs / wallMs，大于 1 说明并行确实省了时间 */
  speedup: number;
  /** 同一时刻最多有几个节点在并行 */
  maxConcurrency: number;
}

/** 兼容 ISO 字符串与 Jackson 的数组形式 [y, m, d, h, mi, s, nano]。 */
export const parseTimestamp = (value: string | number[] | null | undefined): number | null => {
  if (value == null) {
    return null;
  }
  if (Array.isArray(value)) {
    const [y, mo, d, h = 0, mi = 0, s = 0, nano = 0] = value;
    return new Date(y, mo - 1, d, h, mi, s, Math.floor(nano / 1_000_000)).getTime();
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
};

export const buildTimeline = (snapshots: TimelineSnapshot[]): TimelineModel => {
  const label = (s: TimelineSnapshot) => s.nodeName || s.nodeType || s.nodeId;
  const ordered = [...snapshots].sort((a, b) => (a.executionOrder ?? 0) - (b.executionOrder ?? 0));

  const timed: Array<{ s: TimelineSnapshot; start: number; end: number }> = [];
  const notRun: TimelineModel['notRun'] = [];

  for (const s of ordered) {
    const start = parseTimestamp(s.startedAt);
    if (start == null) {
      notRun.push({ nodeId: s.nodeId, label: label(s), status: s.status });
      continue;
    }
    const completed = parseTimestamp(s.completedAt);
    const end = completed ?? (s.duration != null ? start + s.duration : start);
    timed.push({ s, start, end: Math.max(end, start) });
  }

  if (timed.length === 0) {
    return { bars: [], notRun, wallMs: 0, sumMs: 0, speedup: 1, maxConcurrency: 0 };
  }

  const origin = Math.min(...timed.map((t) => t.start));
  const bars: TimelineBar[] = timed
    .map(({ s, start, end }) => ({
      nodeId: s.nodeId,
      label: label(s),
      status: s.status,
      startMs: start - origin,
      endMs: end - origin,
      // 记录下来的耗时比起止时间差更准（起止时间受落库延迟影响），缺失时才退回差值
      durationMs: s.duration != null ? s.duration : end - start,
    }))
    .sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);

  const wallMs = Math.max(...bars.map((b) => b.endMs));
  const sumMs = bars.reduce((total, b) => total + b.durationMs, 0);

  // 扫描线求最大并发：开始 +1，结束 -1，同一时刻先处理结束，避免首尾相接的两个节点被算成并行
  const edges: Array<[number, number]> = [];
  for (const b of bars) {
    if (b.endMs > b.startMs) {
      edges.push([b.startMs, 1], [b.endMs, -1]);
    }
  }
  edges.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let running = 0;
  let maxConcurrency = bars.length > 0 ? 1 : 0;
  for (const [, delta] of edges) {
    running += delta;
    maxConcurrency = Math.max(maxConcurrency, running);
  }

  return {
    bars,
    notRun,
    wallMs,
    sumMs,
    speedup: wallMs > 0 ? Math.max(1, sumMs / wallMs) : 1,
    maxConcurrency,
  };
};
