import { useEffect, useState } from 'react';
import { Collapse, Tag, message } from 'antd';
import { getNodeTypes, NodeDefinition } from '../api/workflow';
import { getNodeVisual } from '../utils/nodeVisuals';

interface NodePanelProps {
  onDragStart: (event: React.DragEvent, nodeType: string, displayName: string) => void;
}

/**
 * 左侧节点面板组件
 */
const NodePanel = ({ onDragStart }: NodePanelProps) => {
  const [nodeTypes, setNodeTypes] = useState<NodeDefinition[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadNodeTypes();
  }, []);

  const loadNodeTypes = async () => {
    setLoading(true);
    try {
      const result = await getNodeTypes();
      console.log('Node types API result:', result);
      if (result.code === 200) {
        setNodeTypes(result.data);
      } else {
        console.error('Failed to load node types:', result);
        message.error(`加载节点类型失败: ${result.message || '未知错误'}`);
      }
    } catch (error) {
      console.error('Error loading node types:', error);
      message.error(`加载节点类型失败: ${error instanceof Error ? error.message : '网络错误'}`);
    } finally {
      setLoading(false);
    }
  };

  // 按分类分组节点
  const llmNodes = nodeTypes.filter((node) => node.category === 'LLM');
  const toolNodes = nodeTypes.filter((node) => node.category === 'TOOL');
  const controlNodes = nodeTypes.filter((node) => node.category === 'CONTROL');

  const renderNodeItem = (node: NodeDefinition) => {
    // 与画布上的节点卡片共用同一份外观（徽标文字 + 颜色），拖出来的节点一眼就能对上
    const visual = getNodeVisual(node.nodeType);
    return (
    <div
      key={node.nodeType}
      draggable
      onDragStart={(e) => onDragStart(e, node.nodeType, node.displayName)}
      className="node-library-item"
    >
      <div className={`node-library-icon ${visual.tone}`}>{visual.badge}</div>
      <div className="min-w-0 flex-1">
        <div className="node-library-title">{node.displayName}</div>
        <div className="node-library-meta">{node.nodeType}</div>
      </div>
      <span className="node-library-drag" aria-hidden="true">⋮⋮</span>
    </div>
    );
  };

  const items = [
    {
      key: 'llm',
      label: (
        <div className="node-library-section-title">
          <span>大模型节点</span>
          <Tag color="blue">{llmNodes.length}</Tag>
        </div>
      ),
      children: (
        <div className="space-y-2">
          {llmNodes.length > 0 ? (
            llmNodes.map(renderNodeItem)
          ) : (
            <div className="text-gray-400 text-center py-4">暂无节点</div>
          )}
        </div>
      ),
    },
    {
      key: 'tool',
      label: (
        <div className="node-library-section-title">
          <span>工具节点</span>
          <Tag color="gold">{toolNodes.length}</Tag>
        </div>
      ),
      children: (
        <div className="space-y-2">
          {toolNodes.length > 0 ? (
            toolNodes.map(renderNodeItem)
          ) : (
            <div className="text-gray-400 text-center py-4">暂无节点</div>
          )}
        </div>
      ),
    },
    ...(controlNodes.length > 0
      ? [
          {
            key: 'control',
            label: (
              <div className="node-library-section-title">
                <span>控制节点</span>
                <Tag color="purple">{controlNodes.length}</Tag>
              </div>
            ),
            children: (
              <div className="space-y-2">
                {controlNodes.map(renderNodeItem)}
              </div>
            ),
          },
        ]
      : []),
  ];

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <div className="node-library-header">
        <div>
          <h3 className="node-library-heading">节点库</h3>
          <p className="node-library-desc">流程组件</p>
        </div>
      </div>
      <div className="node-library-body">
        {loading ? (
          <div className="text-center py-8 text-gray-400">加载中...</div>
        ) : (
          <>
            <Collapse
	              defaultActiveKey={['llm', 'tool', 'control']}
              ghost
              items={items}
              bordered={false}
            />
            <div className="node-library-tip">输入 · 模型 · 工具 · 控制 · 输出</div>
          </>
        )}
      </div>
    </div>
  );
};

export default NodePanel;
