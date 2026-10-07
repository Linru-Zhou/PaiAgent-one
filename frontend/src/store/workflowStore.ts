import { useMemo } from 'react';
import { create } from 'zustand';
import {
  Node,
  Edge,
  Connection,
  NodeChange,
  EdgeChange,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges,
} from '@xyflow/react';
import { createDefaultWorkflowNodes, normalizeWorkflowNode, normalizeWorkflowNodes } from '../utils/workflowNode';
import { decorateEdge } from '../utils/workflowEdge';

export type NodeRunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

/**
 * 画布状态的唯一数据源。
 *
 * 之前 ReactFlow 自己持有一份 nodes/edges（useNodesState），Zustand 里又有一份，两边靠
 * "回声标记 + setTimeout(0)" 双向同步，任何一次漏标记都会出现"改了又被覆盖"的问题。
 * 现在 ReactFlow 以受控模式直接读写这里：onNodesChange / onEdgesChange / onConnect 都是 store 的 action，
 * 同步更新，没有副本也就没有同步问题。
 *
 * 选中的节点只存 id（selectedNodeId），节点内容始终从 nodes 里取，避免保存一份会过期的拷贝。
 */
interface WorkflowState {
  nodes: Node[];
  edges: Edge[];
  selectedNodeId: string | null;
  currentWorkflowId: number | null;
  /** 最近一次执行中各节点的状态，用于在画布上高亮运行中 / 失败的节点 */
  runStatus: Record<string, NodeRunStatus>;

  setNodes: (nodes: Node[]) => void;
  setEdges: (edges: Edge[]) => void;
  setSelectedNode: (node: Pick<Node, 'id'> | null) => void;
  setCurrentWorkflowId: (id: number | null) => void;
  setRunStatus: (status: Record<string, NodeRunStatus>) => void;

  onNodesChange: (changes: NodeChange[]) => void;
  onEdgesChange: (changes: EdgeChange[]) => void;
  onConnect: (connection: Connection) => void;

  addNode: (node: Node) => void;
  updateNode: (id: string, data: Record<string, unknown>) => void;
  deleteNode: (id: string) => void;
  clear: () => void;
}

export const useWorkflowStore = create<WorkflowState>((set) => ({
  nodes: createDefaultWorkflowNodes(),
  edges: [],
  selectedNodeId: null,
  currentWorkflowId: null,
  runStatus: {},

  setNodes: (nodes) => set({ nodes: normalizeWorkflowNodes(nodes) }),

  setEdges: (edges) => set({ edges: edges.map(decorateEdge) }),

  setSelectedNode: (node) => set({ selectedNodeId: node ? node.id : null }),

  setCurrentWorkflowId: (id) => set({ currentWorkflowId: id }),

  setRunStatus: (runStatus) => set({ runStatus }),

  onNodesChange: (changes) => set((state) => {
    const removed = new Set(changes.filter((c) => c.type === 'remove').map((c) => c.id));
    return {
      nodes: applyNodeChanges(changes, state.nodes),
      selectedNodeId: state.selectedNodeId && removed.has(state.selectedNodeId) ? null : state.selectedNodeId,
    };
  }),

  onEdgesChange: (changes) => set((state) => ({ edges: applyEdgeChanges(changes, state.edges) })),

  onConnect: (connection) => set((state) => ({ edges: addEdge(decorateEdge(connection as Edge), state.edges) })),

  addNode: (node) => set((state) => ({ nodes: [...state.nodes, normalizeWorkflowNode(node)] })),

  updateNode: (id, data) => set((state) => ({
    nodes: state.nodes.map((node) =>
      node.id === id ? { ...node, data: { ...node.data, ...data } } : node
    ),
  })),

  deleteNode: (id) => set((state) => ({
    nodes: state.nodes.filter((node) => node.id !== id),
    edges: state.edges.filter((edge) => edge.source !== id && edge.target !== id),
    selectedNodeId: state.selectedNodeId === id ? null : state.selectedNodeId,
  })),

  clear: () => set({
    nodes: createDefaultWorkflowNodes(),
    edges: [],
    selectedNodeId: null,
    currentWorkflowId: null,
    runStatus: {},
  }),
}));

/**
 * 当前选中的节点，始终从 nodes 里派生，不存拷贝。
 * 只有 id 或 data 的引用变化时才返回新对象：拖动节点（只改 position）不会让依赖它的 effect 重复触发。
 */
export const useSelectedNode = (): Node | null => {
  const id = useWorkflowStore((s) => s.selectedNodeId);
  const data = useWorkflowStore((s) => (s.selectedNodeId ? s.nodes.find((n) => n.id === s.selectedNodeId)?.data : undefined));
  return useMemo(() => {
    if (!id || !data) {
      return null;
    }
    return useWorkflowStore.getState().nodes.find((n) => n.id === id) ?? null;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- data 引用变化才需要重新取节点
  }, [id, data]);
};
