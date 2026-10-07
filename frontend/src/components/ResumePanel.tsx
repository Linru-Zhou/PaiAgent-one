import { useState } from 'react';
import { Alert, Button, Input, Select, Switch } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { ResumeExecutionRequest } from '../api/workflow';
import { parseModifiedVariables } from '../utils/resumeRequest';

const { TextArea } = Input;

interface ResumePanelProps {
  /** 失败的节点（默认从这里续跑） */
  failedNodes: Array<{ nodeId: string; nodeName: string; error?: string }>;
  /** 可选的起点：本次执行里出现过的全部节点 */
  allNodes: Array<{ nodeId: string; nodeName: string }>;
  loading: boolean;
  onResume: (request: ResumeExecutionRequest) => void;
}

/**
 * 断点续跑面板：默认从失败节点续跑，已成功的节点直接复用，不会重复执行；
 * 也可以指定从某个节点重跑，或先改写变量再续跑（例如修正导致失败的输入）。
 */
const ResumePanel = ({ failedNodes, allNodes, loading, onResume }: ResumePanelProps) => {
  const [startNodeId, setStartNodeId] = useState<string | undefined>();
  const [skipSuccess, setSkipSuccess] = useState(true);
  const [variablesText, setVariablesText] = useState('');
  const [variablesError, setVariablesError] = useState('');

  const submit = () => {
    if (loading) {
      return; // 防重复点击：执行中直接忽略
    }
    const parsed = parseModifiedVariables(variablesText);
    if (!parsed.ok) {
      setVariablesError(parsed.error);
      return;
    }
    setVariablesError('');
    onResume({
      startNodeId,
      skipSuccessNodes: skipSuccess,
      useSnapshotVariables: true,
      modifiedVariables: parsed.value,
    });
  };

  return (
    <div className="resume-panel">
      <Alert
        type="error"
        showIcon
        message={failedNodes.length > 0
          ? `失败节点：${failedNodes.map((n) => n.nodeName).join('、')}`
          : '本次执行失败'}
        description={failedNodes.find((n) => n.error)?.error}
      />
      <div className="resume-field">
        <div className="debug-field-label">从哪个节点开始（默认：失败节点）</div>
        <Select
          allowClear
          value={startNodeId}
          onChange={setStartNodeId}
          placeholder="失败节点"
          disabled={loading}
          style={{ width: '100%' }}
          options={allNodes.map((n) => ({ value: n.nodeId, label: n.nodeName }))}
        />
      </div>
      <div className="resume-field resume-switch">
        <Switch checked={skipSuccess} onChange={setSkipSuccess} disabled={loading} size="small" />
        <span>复用已成功节点的结果，不重复执行</span>
      </div>
      <div className="resume-field">
        <div className="debug-field-label">改写变量后续跑（可选，JSON 对象）</div>
        <TextArea
          rows={3}
          value={variablesText}
          onChange={(e) => {
            setVariablesText(e.target.value);
            setVariablesError('');
          }}
          disabled={loading}
          placeholder='例如 {"input": "修正后的输入"}'
          status={variablesError ? 'error' : undefined}
        />
        {variablesError && <div className="resume-error">{variablesError}</div>}
      </div>
      <Button icon={<ReloadOutlined />} type="primary" onClick={submit} loading={loading} disabled={loading} block>
        {startNodeId ? '从所选节点继续执行' : '从失败节点继续执行'}
      </Button>
    </div>
  );
};

export default ResumePanel;
