import { Node } from '@xyflow/react';

const REACT_FLOW_NODE_TYPE = 'workflow';

const getFallbackLabel = (nodeType: string, nodeId: string) => {
  switch (nodeType) {
    case 'input':
      return '输入';
    case 'output':
      return '输出';
    case 'tts':
      return '语音合成';
    case 'llm':
      return '大模型';
    case 'react_agent':
      return 'ReAct Agent';
    default:
      return nodeType || nodeId;
  }
};

export const getWorkflowNodeType = (node: Pick<Node, 'type' | 'data'>) => {
  const dataType = typeof node.data?.type === 'string' ? node.data.type : '';
  if (dataType) {
    return dataType;
  }

  return node.type && node.type !== REACT_FLOW_NODE_TYPE ? node.type : '';
};

const DEFAULT_NODE_WIDTH = 220;

export const normalizeWorkflowNode = (node: Node): Node => {
  const workflowNodeType = getWorkflowNodeType(node);

  return {
    ...node,
    type: REACT_FLOW_NODE_TYPE,
    // 只在节点从未被手动调整过大小时补默认宽度，避免每次同步都把用户拖拽出来的尺寸冲掉
    width: node.width ?? DEFAULT_NODE_WIDTH,
    data: {
      ...node.data,
      type: workflowNodeType,
      label: typeof node.data?.label === 'string' && node.data.label.trim()
        ? node.data.label
        : getFallbackLabel(workflowNodeType, node.id),
    },
  };
};

export const normalizeWorkflowNodes = (nodes: Node[]) => nodes.map(normalizeWorkflowNode);

export const serializeWorkflowNodes = (nodes: Node[]) =>
  nodes.map((node) => ({
    id: node.id,
    type: getWorkflowNodeType(node) || node.type,
    position: node.position,
    data: {
      ...node.data,
      type: getWorkflowNodeType(node) || node.type,
    },
  }));

export const createDefaultWorkflowNodes = (): Node[] => [
  {
    id: 'input-default',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 250, y: 100 },
    data: {
      label: '输入节点',
      type: 'input',
    },
  },
  {
    id: 'output-default',
    type: REACT_FLOW_NODE_TYPE,
    position: { x: 250, y: 400 },
    data: {
      label: '输出节点',
      type: 'output',
      outputParams: [],
      responseContent: '',
    },
  },
];
