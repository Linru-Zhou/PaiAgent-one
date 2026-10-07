import { useState } from 'react';
import { Alert, Button, Popconfirm, Table, Tag } from 'antd';
import { SwapOutlined } from '@ant-design/icons';
import { executeWorkflowOnEngine } from '../api/workflow';
import { DiffReport, DiffRow, RowVerdict, diffExecutions, toDiffExecution } from '../utils/executionDiff';

interface EngineCompareProps {
  workflowId: number;
  inputData: string;
  disabled: boolean;
}

const VERDICT: Record<RowVerdict, { text: string; color: string }> = {
  same: { text: '一致', color: 'success' },
  'output-differs': { text: '输出文本不同', color: 'warning' },
  'status-differs': { text: '状态不一致', color: 'error' },
  'only-in-a': { text: '仅 DAG 执行', color: 'error' },
  'only-in-b': { text: '仅 LangGraph 执行', color: 'error' },
};

const ms = (value?: number) => (value == null ? '-' : value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${value}ms`);

/**
 * 双引擎对比：同一个工作流、同一份输入，依次在 DAG 与 LangGraph 引擎上各跑一次，逐节点对比。
 * 判定"一致"只看确定性的部分（执行了哪些节点、各节点状态）；LLM 文本每次都不同，只作提示。
 */
const EngineCompare = ({ workflowId, inputData, disabled }: EngineCompareProps) => {
  const [running, setRunning] = useState<'' | 'dag' | 'langgraph'>('');
  const [report, setReport] = useState<DiffReport | null>(null);
  const [error, setError] = useState('');

  const run = async () => {
    setError('');
    setReport(null);
    try {
      setRunning('dag');
      const dag = await executeWorkflowOnEngine(workflowId, inputData, 'dag');
      if (dag.code !== 200) throw new Error(`DAG 引擎：${dag.message}`);
      setRunning('langgraph');
      const lg = await executeWorkflowOnEngine(workflowId, inputData, 'langgraph');
      if (lg.code !== 200) throw new Error(`LangGraph 引擎：${lg.message}`);
      setReport(diffExecutions(toDiffExecution(dag.data), toDiffExecution(lg.data)));
    } catch (e) {
      setError(e instanceof Error ? e.message : '对比失败');
    } finally {
      setRunning('');
    }
  };

  return (
    <div className="engine-compare">
      <Popconfirm
        title="将把工作流真实执行两次"
        description="含写入记忆 / 知识库 / 调用外部服务的节点会产生两份副作用，LLM 节点会消耗两次 token。确定继续吗？"
        okText="继续对比"
        cancelText="取消"
        onConfirm={run}
        disabled={disabled || !!running || !inputData.trim()}
      >
        <Button icon={<SwapOutlined />} block loading={!!running} disabled={disabled || !!running || !inputData.trim()}>
          {running ? `正在 ${running === 'dag' ? 'DAG' : 'LangGraph'} 引擎上执行...` : '双引擎对比（DAG vs LangGraph）'}
        </Button>
      </Popconfirm>
      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 8 }} />}
      {report && (
        <>
          <Alert
            style={{ margin: '8px 0' }}
            showIcon
            type={report.consistent ? 'success' : 'error'}
            message={report.consistent ? '两个引擎的执行行为一致' : '两个引擎的执行行为不一致'}
            description={`DAG：${report.statusA} ${ms(report.durationA)}  ·  LangGraph：${report.statusB} ${ms(report.durationB)}`
              + (report.outputsIdentical ? '  ·  所有输出逐字相同' : '  ·  部分输出文本不同（LLM 本身有随机性，不算不一致）')}
          />
          <Table<DiffRow>
            size="small"
            pagination={false}
            rowKey="nodeId"
            dataSource={report.rows}
            columns={[
              { title: '节点', dataIndex: 'nodeName', ellipsis: true },
              { title: 'DAG', width: 92, render: (_, r) => `${r.a?.status ?? '-'} ${ms(r.a?.duration)}` },
              { title: 'LangGraph', width: 92, render: (_, r) => `${r.b?.status ?? '-'} ${ms(r.b?.duration)}` },
              { title: '结论', width: 108, render: (_, r) => <Tag color={VERDICT[r.verdict].color}>{VERDICT[r.verdict].text}</Tag> },
            ]}
          />
        </>
      )}
    </div>
  );
};

export default EngineCompare;
