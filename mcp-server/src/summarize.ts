import type { ExecutionDto, NodeResultDto, WorkflowDto } from './client.ts';

export const VIDEO_TASK_PENDING = 'VIDEO_TASK_PENDING';
/** 还没有结束的状态：CANCELLING 是"取消已受理，节点正在退出"，此时还不能当作结束 */
const IN_PROGRESS = new Set(['RUNNING', 'CANCELLING']);
const URL_PATTERN = /https?:\/\/[^\s"'<>\\)\]]+/g;
const MAX_ARTIFACTS = 10;
const NODE_PREVIEW_CHARS = 300;
const POLL_SECONDS = 4;

export interface WorkflowSummary {
  id: number;
  name: string;
  description: string;
  input_label?: string;
  node_count?: number;
}

/** 列表里不返回 flowData（动辄几十 KB，还会撑爆模型上下文），只从里面取两个对选工作流有用的信息。 */
export function summarizeWorkflow(workflow: WorkflowDto): WorkflowSummary {
  const summary: WorkflowSummary = {
    id: workflow.id,
    name: workflow.name,
    description: workflow.description ?? '',
  };
  if (workflow.flowData) {
    try {
      const flow = JSON.parse(workflow.flowData) as { nodes?: Array<{ type?: string; data?: { label?: string } }> };
      const nodes = Array.isArray(flow.nodes) ? flow.nodes : [];
      summary.node_count = nodes.length;
      const label = nodes.find((node) => node.type === 'input')?.data?.label;
      if (label) {
        summary.input_label = label;
      }
    } catch {
      // 工作流数据解析不了就只给基本信息
    }
  }
  return summary;
}

function truncate(text: string, max: number): { text: string; truncated: boolean } {
  return text.length <= max ? { text, truncated: false } : { text: text.slice(0, max), truncated: true };
}

function extractArtifacts(text: string | null | undefined): string[] {
  if (!text) {
    return [];
  }
  return [...new Set(text.match(URL_PATTERN) ?? [])].slice(0, MAX_ARTIFACTS);
}

function nodeView(node: NodeResultDto): Record<string, unknown> {
  const output = node.output ? truncate(node.output, NODE_PREVIEW_CHARS) : undefined;
  return {
    node_id: node.nodeId,
    name: node.nodeName ?? node.nodeId,
    status: node.status,
    ...(node.duration != null ? { duration_ms: node.duration } : {}),
    ...(node.error ? { error: node.error } : {}),
    ...(node.errorCode ? { error_code: node.errorCode } : {}),
    ...(output ? { output_preview: output.text, ...(output.truncated ? { output_truncated: true } : {}) } : {}),
  };
}

/**
 * 把后端的执行记录整理成给模型看的结构：默认是摘要（状态、进度、失败节点、最终输出），
 * detail=nodes 才附上每个节点；节点的输入不返回，输出只给预览，产物（图片/音频/视频）只给链接。
 */
export function summarizeExecution(
  workflowId: number,
  execution: ExecutionDto,
  detail: 'summary' | 'nodes',
  maxOutputChars: number,
): Record<string, unknown> {
  const nodes = execution.nodeResults ?? [];
  const done = !IN_PROGRESS.has(execution.status);
  const failedNode = nodes.find((node) => node.status === 'FAILED');
  const view: Record<string, unknown> = {
    ok: true,
    workflow_id: workflowId,
    execution_id: execution.executionId,
    status: execution.status,
    done,
    ...(execution.duration != null ? { duration_ms: execution.duration } : {}),
    progress: {
      nodes_seen: nodes.length,
      succeeded: nodes.filter((node) => node.status === 'SUCCESS').length,
      running: nodes.filter((node) => node.status === 'RUNNING').length,
      failed: nodes.filter((node) => node.status === 'FAILED').length,
      cancelled: nodes.filter((node) => node.status === 'CANCELLED').length,
    },
  };

  if (failedNode) {
    view.failed_node = {
      node_id: failedNode.nodeId,
      name: failedNode.nodeName ?? failedNode.nodeId,
      error: failedNode.error ?? execution.errorMessage ?? '',
      ...(failedNode.errorCode ? { error_code: failedNode.errorCode } : {}),
      ...(failedNode.errorDetail ? { error_detail: failedNode.errorDetail } : {}),
    };
  }
  if (execution.errorMessage && execution.status === 'FAILED') {
    view.error = execution.errorMessage;
  }
  if (execution.outputData) {
    const output = truncate(execution.outputData, maxOutputChars);
    view.output = output.text;
    if (output.truncated) {
      view.output_truncated = true;
    }
    const artifacts = extractArtifacts(execution.outputData);
    if (artifacts.length > 0) {
      view.artifacts = artifacts;
    }
  }
  if (detail === 'nodes') {
    view.nodes = nodes.map(nodeView);
  }

  Object.assign(view, nextStep(execution, failedNode));
  return view;
}

function nextStep(execution: ExecutionDto, failedNode: NodeResultDto | undefined): Record<string, unknown> {
  if (execution.status === 'RUNNING') {
    return {
      next_step: `仍在运行。${POLL_SECONDS} 秒后再调用 get_execution 查看；不要再次调用 start_workflow（会重复执行并重复计费）。`,
      retry_after_seconds: POLL_SECONDS,
    };
  }
  if (execution.status === 'CANCELLING') {
    return {
      next_step: `正在取消：已受理，运行中的节点正在退出。${POLL_SECONDS} 秒后再调用 get_execution，直到 status 变成 CANCELLED；不需要再次取消。`,
      retry_after_seconds: POLL_SECONDS,
    };
  }
  if (execution.status === 'CANCELLED') {
    return {
      next_step: '已取消。取消前已完成的节点结果保留；如果用户之后想继续，调用 resume_execution 只会重跑没完成的节点；想从头来过就用 start_workflow。',
    };
  }
  if (execution.status === 'SUCCESS') {
    return { next_step: '已完成。把 output 交给用户；如有 artifacts 链接（图片/音频/视频），原样转给用户，不要改写。' };
  }
  if (failedNode?.errorCode === VIDEO_TASK_PENDING) {
    return {
      needs_decision: true,
      next_step: '上次提交的视频任务还没取回结果，需要用户选择：「continue」继续等待那个任务，或「regenerate」重新提交（会产生新的费用）。'
        + '把两个选项告诉用户，拿到答案后调用 resume_execution 并设置 video_task_action。',
    };
  }
  return {
    next_step: `执行失败${failedNode ? `（节点: ${failedNode.nodeName ?? failedNode.nodeId}）` : ''}。`
      + '如果错误指向参数、模型名或密钥（例如 4xx、API Key 无效），先告诉用户去修正配置，不要盲目重试；'
      + '如果是超时或服务繁忙，可以调用 resume_execution，只会重跑失败的节点，已成功的节点复用结果。',
  };
}
