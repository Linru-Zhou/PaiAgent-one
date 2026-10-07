import { Edge, MarkerType } from '@xyflow/react';

export const WORKFLOW_EDGE_TYPE = 'workflow-edge';

export const WORKFLOW_EDGE_STYLE = {
  stroke: '#8b9bb4',
  strokeWidth: 2,
};

export const WORKFLOW_EDGE_MARKER = {
  type: MarkerType.ArrowClosed,
  width: 20,
  height: 20,
};

/** 统一连线的外观：无论是从后端加载的旧数据还是用户新连的，都走同一个出口。 */
export const decorateEdge = (edge: Edge): Edge => ({
  ...edge,
  type: WORKFLOW_EDGE_TYPE,
  style: WORKFLOW_EDGE_STYLE,
  markerEnd: WORKFLOW_EDGE_MARKER,
});
