import { useEffect, useMemo, useState } from 'react';
import { Alert, Empty, Spin, Tag, Tooltip } from 'antd';
import { ExecutionSnapshot, getExecutionSnapshots } from '../api/workflow';
import { buildTimeline } from '../utils/executionTimeline';

interface ExecutionTimelineProps {
  workflowId: number;
  executionId: number;
  /** 执行状态变化时重新拉取（执行中 → 结束） */
  refreshKey: string;
}

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: '#22c55e',
  FAILED: '#ef4444',
  RUNNING: '#2563eb',
  PENDING: '#94a3b8',
  SKIPPED: '#cbd5e1',
};

const formatMs = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${Math.round(ms)}ms`);

/**
 * 执行时间线（甘特图）：横轴是相对开始时间，每个节点一条横条。
 * 同一时间段内重叠的横条就是并行执行的节点；顶部汇总"串行需要多久 vs 实际墙钟多久"。
 */
const ExecutionTimeline = ({ workflowId, executionId, refreshKey }: ExecutionTimelineProps) => {
  const [snapshots, setSnapshots] = useState<ExecutionSnapshot[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    getExecutionSnapshots(workflowId, executionId)
      .then((result) => {
        if (!cancelled) {
          setSnapshots(result.code === 200 ? result.data || [] : []);
          if (result.code !== 200) setError(result.message || '加载时间线失败');
        }
      })
      .catch(() => {
        if (!cancelled) setError('加载时间线失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [workflowId, executionId, refreshKey]);

  const model = useMemo(() => buildTimeline(snapshots), [snapshots]);
  const axisMax = Math.max(model.wallMs, 1);

  if (error) {
    return <Alert type="warning" showIcon message={error} />;
  }
  if (loading && snapshots.length === 0) {
    return <Spin size="small" />;
  }
  if (model.bars.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可绘制的节点耗时" />;
  }

  return (
    <div className="exec-timeline">
      <div className="exec-timeline-summary">
        <Tag color="blue">墙钟 {formatMs(model.wallMs)}</Tag>
        <Tag>各节点耗时之和 {formatMs(model.sumMs)}</Tag>
        <Tag color={model.maxConcurrency > 1 ? 'green' : 'default'}>
          最大并行 {model.maxConcurrency} 个{model.maxConcurrency > 1 ? `，约 ${model.speedup.toFixed(1)}x 加速` : ''}
        </Tag>
      </div>
      {model.bars.map((bar) => (
        <div className="exec-timeline-row" key={bar.nodeId}>
          <Tooltip title={`${bar.label}：${formatMs(bar.durationMs)}（${bar.status}）`}>
            <span className="exec-timeline-label">{bar.label}</span>
          </Tooltip>
          <div className="exec-timeline-track">
            <div
              className="exec-timeline-bar"
              style={{
                left: `${(bar.startMs / axisMax) * 100}%`,
                width: `${Math.max(((bar.endMs - bar.startMs) / axisMax) * 100, 0.8)}%`,
                background: STATUS_COLOR[bar.status] || STATUS_COLOR.PENDING,
              }}
            />
          </div>
          <span className="exec-timeline-ms">{formatMs(bar.durationMs)}</span>
        </div>
      ))}
      {model.notRun.length > 0 && (
        <div className="exec-timeline-notrun">
          未执行：{model.notRun.map((n) => `${n.label}（${n.status}）`).join('、')}
        </div>
      )}
    </div>
  );
};

export default ExecutionTimeline;
