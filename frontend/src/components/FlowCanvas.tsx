import { useCallback, useEffect, useRef } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Node,
  NodeProps,
  Connection,
  Handle,
  Position,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges,
  useNodesState,
  useEdgesState,
  useReactFlow,
  BaseEdge,
  EdgeToolbar,
  NodeResizer,
  getSmoothStepPath,
  EdgeProps,
  OnNodesChange,
  OnEdgesChange,
  OnConnect,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useWorkflowStore } from '../store/workflowStore';

interface FlowCanvasProps {
  onNodeClick: (node: Node) => void;
}

type WorkflowNodeData = {
  label?: string;
  type?: string;
  provider?: string;
  model?: string;
  skillName?: string;
  inputParams?: unknown[];
  outputParams?: unknown[];
  maxSteps?: number;
  agentStrategy?: string;
  tools?: string[];
  knowledgeBaseId?: string;
  knowledgeBaseName?: string;
  conditions?: Array<{ id: string; field?: string; operator?: string; value?: string }>;
};

type WorkflowCardNode = Node<WorkflowNodeData, 'workflow'>;

const WORKFLOW_EDGE_STYLE = {
  stroke: '#8b9bb4',
  strokeWidth: 2,
};

const WORKFLOW_EDGE_MARKER = {
  type: MarkerType.ArrowClosed,
  width: 20,
  height: 20,
};

const getDefaultNodeData = (type: string) => {
  switch (type) {
    case 'image_generate':
      return {
        inputParams: [{ name: 'prompt', type: 'reference', value: '' }],
        outputParams: [{ name: 'image_url', value: 'imageUrl' }],
      };
    case 'video_generate':
      return {
        inputParams: [{ name: 'prompt', type: 'reference', value: '' }],
        outputParams: [{ name: 'video_url', value: 'videoUrl' }],
      };
    default:
      return {};
  }
};

const nodeMeta: Record<string, { icon: string; tone: string; caption: string }> = {
  input: { icon: 'IN', tone: 'node-tone-green', caption: 'Start input' },
  output: { icon: 'OUT', tone: 'node-tone-purple', caption: 'Final response' },
  llm: { icon: 'AI', tone: 'node-tone-blue', caption: 'Model call' },
  react_agent: { icon: 'AI', tone: 'node-tone-blue', caption: 'ReAct compatibility' },
  openai: { icon: 'AI', tone: 'node-tone-blue', caption: 'OpenAI' },
  deepseek: { icon: 'DS', tone: 'node-tone-blue', caption: 'DeepSeek' },
  qwen: { icon: 'QW', tone: 'node-tone-blue', caption: 'Qwen' },
  zhipu: { icon: 'ZP', tone: 'node-tone-blue', caption: 'ZhiPu' },
  ai_ping: { icon: 'AP', tone: 'node-tone-blue', caption: 'AIPing' },
  tts: { icon: 'TTS', tone: 'node-tone-amber', caption: 'Audio tool' },
  condition: { icon: 'IF', tone: 'node-tone-orange', caption: 'Condition branch' },
};

const getNodeMeta = (type?: string) => (
  nodeMeta[type || ''] || { icon: 'FN', tone: 'node-tone-slate', caption: 'Workflow node' }
);

const getToolLabels = (tools: string[]) => {
  const labels: string[] = [];
  if (tools.includes('web_search') || tools.includes('web_fetch')) {
    labels.push('联网搜索');
  }
  if (tools.includes('memory_write')) {
    labels.push('写入记忆');
  }
  return labels;
};

const getKnowledgeBaseLabel = (data: WorkflowNodeData) => {
  if (!data.knowledgeBaseId) {
    return '';
  }
  return data.knowledgeBaseName || '已启用';
};

const WorkflowNodeCard = ({ id, data, selected }: NodeProps<WorkflowCardNode>) => {
  const workflowType = data.type || '';
  const meta = getNodeMeta(workflowType);
  const inputCount = Array.isArray(data.inputParams) ? data.inputParams.length : 0;
  const outputCount = Array.isArray(data.outputParams) ? data.outputParams.length : 0;
  const modelLabel = data.model || data.provider || data.skillName || meta.caption;
  const tools = Array.isArray(data.tools) ? data.tools : [];
  const toolLabels = getToolLabels(tools);
  const isReActMode = (workflowType === 'llm' && data.agentStrategy === 'react') || workflowType === 'react_agent';
  const knowledgeBaseLabel = getKnowledgeBaseLabel(data);

  const isCondition = workflowType === 'condition';
  const conditions = Array.isArray(data.conditions) ? data.conditions : [];
  const handleCount = isCondition ? conditions.length + 1 : 0;

  return (
    <div className={`workflow-node ${selected ? 'is-selected' : ''}`}>
      <NodeResizer
        nodeId={id}
        isVisible={selected}
        minWidth={198}
        minHeight={90}
        handleClassName="workflow-node-resize-handle"
        lineClassName="workflow-node-resize-line"
      />
      {workflowType !== 'input' && (
        <Handle
          type="target"
          position={Position.Top}
          className="workflow-node-handle"
        />
      )}
      <div className="workflow-node-header">
        <div className={`workflow-node-icon ${meta.tone}`}>{meta.icon}</div>
        <div className="workflow-node-title-wrap">
          <div className="workflow-node-title">{data.label || workflowType || '未命名节点'}</div>
          <div className="workflow-node-subtitle">{modelLabel}</div>
        </div>
      </div>
      {isCondition && conditions.length > 0 && (
        <div className="workflow-node-conditions">
          {conditions.map((c, i) => (
            <div key={c.id} className="workflow-node-condition-item">
              <span className="condition-badge">{i + 1}</span>
              <span className="condition-label">{c.field} {c.operator} {c.value}</span>
            </div>
          ))}
          <div className="workflow-node-condition-item condition-default">
            <span className="condition-badge default">D</span>
            <span className="condition-label">否则(默认)</span>
          </div>
        </div>
      )}
      {(isReActMode || knowledgeBaseLabel) && (
        <div className="workflow-node-conditions">
          {isReActMode && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge">A</span>
              <span className="condition-label">Agent策略 ReAct</span>
            </div>
          )}
          {isReActMode && toolLabels.length > 0 && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge default">{toolLabels.length}</span>
              <span className="condition-label">工具 {toolLabels.join(', ')}</span>
            </div>
          )}
          {knowledgeBaseLabel && (
            <div className="workflow-node-condition-item">
              <span className="condition-badge knowledge">K</span>
              <span className="condition-label">知识库 {knowledgeBaseLabel}</span>
            </div>
          )}
        </div>
      )}
      <div className="workflow-node-footer">
        <span>{workflowType || 'node'}</span>
        <span>{inputCount} in / {outputCount} out</span>
      </div>
      {workflowType === 'output' ? null : isCondition ? (
        <>
          {conditions.map((c, i) => (
            <Handle
              key={c.id}
              type="source"
              position={Position.Bottom}
              id={c.id}
              className="workflow-node-handle condition-handle"
              style={{ left: `${((i + 1) / (handleCount + 1)) * 100}%` }}
            />
          ))}
          <Handle
            type="source"
            position={Position.Bottom}
            id="default"
            className="workflow-node-handle condition-handle"
            style={{ left: `${(handleCount / (handleCount + 1)) * 100}%` }}
          />
        </>
      ) : (
        <Handle
          type="source"
          position={Position.Bottom}
          className="workflow-node-handle"
        />
      )}
    </div>
  );
};

const nodeTypes = {
  workflow: WorkflowNodeCard,
};

/**
 * 带删除按钮的连线组件。
 * 之前删除连线只能靠"选中后按 Backspace/Delete"，既没有可见的操作入口，
 * 也没有选中态的视觉反馈，用户完全发现不了。这里加一个悬停/选中时出现的
 * 删除按钮，作为始终可用、可发现的删除方式。
 */
const WorkflowEdge = ({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style,
  markerEnd,
}: EdgeProps) => {
  const { deleteElements } = useReactFlow();
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  return (
    <>
      <BaseEdge id={id} path={path} style={style} markerEnd={markerEnd} interactionWidth={24} />
      <EdgeToolbar edgeId={id} x={labelX} y={labelY}>
        <button
          type="button"
          className="workflow-edge-delete-btn"
          title="删除连线"
          onClick={(event) => {
            event.stopPropagation();
            deleteElements({ edges: [{ id }] });
          }}
        >
          ×
        </button>
      </EdgeToolbar>
    </>
  );
};

const edgeTypes = {
  'workflow-edge': WorkflowEdge,
};

/**
 * 中间画布组件
 */
const FlowCanvas = ({ onNodeClick }: FlowCanvasProps) => {
  const { nodes: storeNodes, edges: storeEdges, setNodes: setStoreNodes, setEdges: setStoreEdges } = useWorkflowStore();

  const [nodes, setNodes] = useNodesState(storeNodes);
  const [edges, setEdges] = useEdgesState(storeEdges);

  /*
   * The canvas state (nodes/edges) and the app-level Zustand store both hold a
   * copy of the graph, kept in sync in both directions. A push FROM the canvas
   * (e.g. selecting or deleting an edge) updates the store, which in turn used
   * to unconditionally re-push INTO the canvas — clobbering the just-applied
   * change (and any transient state like `selected`) before React Flow's own
   * delete-key handler could act on it. These refs mark "this store update
   * originated from the canvas itself, skip the echo" so external updates
   * (loading a workflow, editing a node in the Inspector) still flow in.
   */
  const skipNodesSync = useRef(false);
  const skipEdgesSync = useRef(false);

  // 当 store 中的 nodes/edges 变化时，同步更新到本地状态（跳过画布自己发起的回声更新）
  useEffect(() => {
    if (skipNodesSync.current) {
      skipNodesSync.current = false;
      return;
    }
    setNodes(storeNodes);
  }, [storeNodes, setNodes]);

  useEffect(() => {
    if (skipEdgesSync.current) {
      skipEdgesSync.current = false;
      return;
    }
    const edgesWithMarkers = storeEdges.map(edge => ({
      ...edge,
      type: 'workflow-edge',
      style: WORKFLOW_EDGE_STYLE,
      markerEnd: WORKFLOW_EDGE_MARKER,
    }));
    setEdges(edgesWithMarkers);
  }, [storeEdges, setEdges]);

  /*
   * Pushing to the Zustand store must not happen synchronously inside a
   * setState updater (React logs "Cannot update a component while rendering
   * a different component" and the store write is unreliable). Deferring it
   * with setTimeout(0) moves it out of the render phase — this is why the
   * original code did the same, even though it looked like an odd workaround.
   */
  const handleNodesChange: OnNodesChange = useCallback((changes) => {
    setNodes((currentNodes) => {
      const updatedNodes = applyNodeChanges(changes, currentNodes);
      setTimeout(() => {
        skipNodesSync.current = true;
        setStoreNodes(updatedNodes);
      }, 0);
      return updatedNodes;
    });
  }, [setNodes, setStoreNodes]);

  const handleEdgesChange: OnEdgesChange = useCallback((changes) => {
    setEdges((currentEdges) => {
      const updatedEdges = applyEdgeChanges(changes, currentEdges);
      setTimeout(() => {
        skipEdgesSync.current = true;
        setStoreEdges(updatedEdges);
      }, 0);
      return updatedEdges;
    });
  }, [setEdges, setStoreEdges]);

  const handleConnect: OnConnect = useCallback((connection: Connection) => {
    setEdges((eds) => {
      const newEdge = {
        ...connection,
        type: 'workflow-edge',
        style: WORKFLOW_EDGE_STYLE,
        markerEnd: WORKFLOW_EDGE_MARKER,
      };
      const updatedEdges = addEdge(newEdge, eds);
      setTimeout(() => {
        skipEdgesSync.current = true;
        setStoreEdges(updatedEdges);
      }, 0);
      return updatedEdges;
    });
  }, [setEdges, setStoreEdges]);

  // 处理拖拽放置
  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/reactflow-type');
      const label = event.dataTransfer.getData('application/reactflow-label');

      if (!type) return;

      const reactFlowBounds = (event.target as HTMLElement).getBoundingClientRect();
      const position = {
        x: event.clientX - reactFlowBounds.left - 75,
        y: event.clientY - reactFlowBounds.top - 25,
      };

      const newNode: Node = {
        id: `${type}-${Date.now()}`,
        type: 'workflow',
        position,
        data: { label: label || type, type, ...getDefaultNodeData(type) },
      };

      setNodes((nds) => {
        const updatedNodes = nds.concat(newNode);
        setTimeout(() => {
          skipNodesSync.current = true;
          setStoreNodes(updatedNodes);
        }, 0);
        return updatedNodes;
      });
    },
    [setNodes, setStoreNodes]
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onNodeClick(node);
    },
    [onNodeClick]
  );

  return (
    <div className="h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={handleConnect}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onNodeClick={handleNodeClick}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultEdgeOptions={{
          type: 'workflow-edge',
          style: WORKFLOW_EDGE_STYLE,
          markerEnd: WORKFLOW_EDGE_MARKER,
        }}
        fitView
        fitViewOptions={{ padding: 0.28 }}
        snapToGrid
        snapGrid={[16, 16]}
      >
        <Background color="#d8dee9" gap={18} size={1.2} />
        <Controls className="workflow-controls" />
        <MiniMap
          pannable
          zoomable
          className="workflow-minimap"
          nodeColor={(node) => getNodeMeta(String(node.data?.type || '')).tone.includes('green') ? '#22c55e' : '#64748b'}
        />
      </ReactFlow>
    </div>
  );
};

export default FlowCanvas;
