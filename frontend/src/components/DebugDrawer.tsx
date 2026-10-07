import { useEffect, useRef, useState } from 'react';
import { Drawer, Input, Button, Progress, Tag, Collapse, Alert } from 'antd';
import { PlayCircleOutlined, CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import AudioPlayer from './AudioPlayer';
import ExecutionTimeline from './ExecutionTimeline';
import ResumePanel from './ResumePanel';
import EngineCompare from './EngineCompare';
import { useWorkflowStore, NodeRunStatus } from '../store/workflowStore';
import { describeResumeFailure } from '../utils/resumeRequest';
import { buildBackendUrl } from '../config/api';
import { estimateLlmCost, formatCost } from '../utils/llmPricing';
import {
  ExecutionEvent,
  ExecutionNodeResult,
  ExecutionResponse as PersistedExecutionResponse,
  ResumeExecutionRequest,
  executeWorkflowStream,
  getLatestExecution,
  resumeWorkflowExecution,
} from '../api/workflow';

const { TextArea } = Input;

interface NodeResult {
  nodeId: string;
  nodeName: string;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  duration: number;
  error?: string;
}

interface ExecutionResponse {
  executionId: number;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  nodeResults: NodeResult[];
  outputData: unknown;
  duration: number;
  errorMessage?: string;
}

interface DebugDrawerProps {
  open: boolean;
  workflowId: number | null;
  totalNodeCount: number;
  onClose: () => void;
  onExecute: (inputData: string) => Promise<ExecutionResponse>;
}

const isRecord = (value: unknown): value is Record<string, unknown> => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);

const parseJsonIfPossible = (value: string) => {
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return value;
  }
};

const normalizeValue = (value: unknown): unknown => (
  typeof value === 'string' ? parseJsonIfPossible(value) : value
);

const IMAGE_URL_PATTERN = /\.(png|jpe?g|gif|webp|bmp|svg)(?:[?#].*)?$/i;
const AUDIO_URL_PATTERN = /\.(mp3|wav|ogg|m4a|aac|flac)(?:[?#].*)?$/i;
const VIDEO_URL_PATTERN = /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i;

const normalizeMediaUrl = (url: string) => (
  url.startsWith('/') ? buildBackendUrl(url) : url
);

const isLikelyImageUrl = (value: string) => (
  IMAGE_URL_PATTERN.test(value)
  || value.includes('/images/')
  || value.includes('/image/')
);

const isLikelyAudioUrl = (value: string) => (
  AUDIO_URL_PATTERN.test(value)
  || value.startsWith('/audio/')
  || value.includes('/audio/')
);

const isLikelyVideoUrl = (value: string) => (
  VIDEO_URL_PATTERN.test(value)
  || value.includes('/videos/')
  || value.includes('/video/')
);

const extractAudioSrc = (value: string) => {
  if (!value.includes('<audio') || !value.includes('src=')) {
    return null;
  }
  const srcMatch = value.match(/src=["']([^"']+)["']/);
  return srcMatch?.[1] || null;
};

const extractUrls = (value: string) => (
  value.match(/https?:\/\/[^\s"'<>，。；；)）]+|\/[^\s"'<>，。；；)）]+/g) || []
);

const uniqueUrls = (urls: string[]) => Array.from(new Set(
  urls
    .map((url) => url.trim())
    .filter(Boolean)
    .map(normalizeMediaUrl)
));

const collectImageUrls = (value: unknown): string[] => {
  const normalized = normalizeValue(value);
  const record = isRecord(normalized) && isRecord(normalized.output)
    ? normalized.output
    : normalized;

  if (typeof record === 'string') {
    return uniqueUrls(extractUrls(record).filter(isLikelyImageUrl));
  }

  if (!isRecord(record)) {
    return [];
  }

  const urls: string[] = [];
  const appendValue = (candidate: unknown) => {
    if (typeof candidate === 'string') {
      const extractedUrls = extractUrls(candidate).filter(isLikelyImageUrl);
      if (extractedUrls.length > 0) {
        urls.push(...extractedUrls);
      } else if (isLikelyImageUrl(candidate)) {
        urls.push(candidate);
      }
    } else if (Array.isArray(candidate)) {
      candidate.forEach(appendValue);
    }
  };

  appendValue(record.imageUrls);
  appendValue(record.images);
  appendValue(record.imageUrl);
  appendValue(record.image_url);
  appendValue(record.url);
  appendValue(record.output);

  return uniqueUrls(urls);
};

const collectVideoUrls = (value: unknown): string[] => {
  const normalized = normalizeValue(value);
  const record = isRecord(normalized) && isRecord(normalized.output)
    ? normalized.output
    : normalized;

  if (typeof record === 'string') {
    return uniqueUrls(extractUrls(record).filter(isLikelyVideoUrl));
  }

  if (!isRecord(record)) {
    return [];
  }

  const urls: string[] = [];
  const appendValue = (candidate: unknown) => {
    if (typeof candidate === 'string') {
      const extractedUrls = extractUrls(candidate).filter(isLikelyVideoUrl);
      if (extractedUrls.length > 0) {
        urls.push(...extractedUrls);
      } else if (isLikelyVideoUrl(candidate)) {
        urls.push(candidate);
      }
    } else if (Array.isArray(candidate)) {
      candidate.forEach(appendValue);
    }
  };

  appendValue(record.videoUrls);
  appendValue(record.videos);
  appendValue(record.videoUrl);
  appendValue(record.video_url);
  appendValue(record.url);
  appendValue(record.output);

  return uniqueUrls(urls);
};

const collectAudioOutput = (value: unknown): { audioUrl: string; fileName?: string } | null => {
  const normalized = normalizeValue(value);
  const record = isRecord(normalized) && isRecord(normalized.output)
    ? normalized.output
    : normalized;

  if (typeof record === 'string') {
    const embeddedSrc = extractAudioSrc(record);
    const audioUrl = embeddedSrc || (isLikelyAudioUrl(record) ? record : null);
    return audioUrl ? { audioUrl: normalizeMediaUrl(audioUrl) } : null;
  }

  if (!isRecord(record)) {
    return null;
  }

  const fileName = typeof record.fileName === 'string' ? record.fileName : undefined;
  const directAudioUrl = typeof record.audioUrl === 'string'
    ? record.audioUrl
    : typeof record.audio_url === 'string'
      ? record.audio_url
      : null;

  if (directAudioUrl) {
    return { audioUrl: normalizeMediaUrl(directAudioUrl), fileName };
  }

  if (typeof record.output === 'string') {
    const embeddedSrc = extractAudioSrc(record.output);
    const audioUrl = embeddedSrc || (isLikelyAudioUrl(record.output) ? record.output : null);
    if (audioUrl) {
      return { audioUrl: normalizeMediaUrl(audioUrl), fileName };
    }
  }

  return null;
};

const getFriendlyOutputText = (value: unknown): string | null => {
  const normalizedValue = typeof value === 'string' ? parseJsonIfPossible(value) : value;

  if (typeof normalizedValue === 'string') {
    return normalizedValue;
  }

  if (isRecord(normalizedValue) && typeof normalizedValue.output === 'string') {
    return normalizedValue.output;
  }

  return null;
};

const truncateText = (value: unknown, maxLength = 180) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (!text) return '';
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text;
};

const getToolTrace = (value: unknown): Record<string, unknown>[] => {
  const normalized = normalizeValue(value);
  const record = isRecord(normalized) && isRecord(normalized.output)
    ? normalized.output
    : normalized;

  if (!isRecord(record) || !Array.isArray(record.toolTrace)) {
    return [];
  }

  return record.toolTrace.filter((item): item is Record<string, unknown> => isRecord(item));
};

const summarizeToolInput = (toolInput: unknown) => {
  if (!isRecord(toolInput)) {
    return truncateText(toolInput, 120);
  }

  const query = typeof toolInput.query === 'string' ? toolInput.query : '';
  if (query) {
    return `query="${truncateText(query, 120)}"`;
  }

  const urls = Array.isArray(toolInput.urls) ? toolInput.urls : undefined;
  if (urls && urls.length > 0) {
    return `urls=${urls.length}`;
  }

  return truncateText(toolInput, 120);
};

const summarizeObservation = (observation: unknown) => {
  if (!isRecord(observation)) {
    return truncateText(observation, 160);
  }

  if (typeof observation.error === 'string') {
    return `失败: ${truncateText(observation.error, 160)}`;
  }

  const summary = typeof observation.summary === 'string'
    ? observation.summary
    : typeof observation.content === 'string'
      ? observation.content
      : '';
  const citations = Array.isArray(observation.citations) ? observation.citations.length : 0;
  const results = Array.isArray(observation.results) ? observation.results.length : 0;
  const parts = [
    summary ? `结果="${truncateText(summary, 120)}"` : '',
    results > 0 ? `results=${results}` : '',
    citations > 0 ? `citations=${citations}` : '',
  ].filter(Boolean);

  return parts.join('；') || truncateText(observation, 160);
};

const formatToolTraceLog = (traceItem: Record<string, unknown>) => {
  const toolName = typeof traceItem.toolName === 'string' ? traceItem.toolName : '';
  if (!toolName) {
    return null;
  }

  const step = typeof traceItem.step === 'number' ? `#${traceItem.step}` : '';
  const inputSummary = summarizeToolInput(traceItem.toolInput);
  const observationSummary = summarizeObservation(traceItem.observation);
  return `🔎 ReAct 工具调用${step ? ` ${step}` : ''}: ${toolName}${inputSummary ? ` | ${inputSummary}` : ''}${observationSummary ? ` | ${observationSummary}` : ''}`;
};

const formatToolTraceLogs = (output: unknown) => (
  getToolTrace(output)
    .map(formatToolTraceLog)
    .filter((log): log is string => Boolean(log))
);

interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  model?: string;
  provider?: string;
}

const getTokenUsage = (output: unknown): TokenUsage | null => {
  const normalized = normalizeValue(output);
  const record = isRecord(normalized) && isRecord(normalized.output) ? normalized.output : normalized;
  if (!isRecord(record)) {
    return null;
  }
  const totalTokens = typeof record.totalTokens === 'number' ? record.totalTokens : 0;
  const inputTokens = typeof record.inputTokens === 'number' ? record.inputTokens : 0;
  const outputTokens = typeof record.outputTokens === 'number' ? record.outputTokens : 0;
  if (totalTokens <= 0 && inputTokens <= 0 && outputTokens <= 0) {
    return null;
  }
  return {
    inputTokens,
    outputTokens,
    totalTokens: totalTokens || inputTokens + outputTokens,
    model: typeof record.model === 'string' ? record.model : undefined,
    provider: typeof record.provider === 'string' ? record.provider : undefined,
  };
};

const TokenUsageTag = ({ usage }: { usage: TokenUsage }) => {
  const estimate = estimateLlmCost(usage.model || usage.provider, usage.inputTokens, usage.outputTokens);
  return (
    <Tag color="geekblue" title={usage.model ? `模型: ${usage.model}` : undefined}>
      {usage.totalTokens} tokens{estimate ? ` · 约 ${formatCost(estimate)}` : ''}
    </Tag>
  );
};

const formatDuration = (milliseconds: number | string | undefined | null) => {
  const ms = typeof milliseconds === 'string'
    ? Number.parseInt(milliseconds, 10)
    : milliseconds;

  if (!Number.isFinite(ms) || !ms || ms <= 0) {
    return '0秒';
  }

  const totalSeconds = ms / 1000;
  const minutes = Math.floor(totalSeconds / 60);
  let seconds = totalSeconds - minutes * 60;

  if (minutes > 0) {
    seconds = Math.round(seconds);
    if (seconds === 60) {
      return `${minutes + 1}分0秒`;
    }
    return `${minutes}分${seconds}秒`;
  }

  if (totalSeconds < 10) {
    return `${Number(totalSeconds.toFixed(1))}秒`;
  }

  return `${Math.round(totalSeconds)}秒`;
};

const DebugValue = ({ value, preferOutputText = false }: { value: unknown; preferOutputText?: boolean }) => {
  const friendlyText = preferOutputText ? getFriendlyOutputText(value) : null;

  if (friendlyText !== null) {
    return (
      <div className="debug-markdown-output">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {friendlyText}
        </ReactMarkdown>
      </div>
    );
  }

  return (
    <pre className="debug-json-block">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
};

const RichOutput = ({
  value,
  preferOutputText = false,
  renderMedia = false,
}: {
  value: unknown;
  preferOutputText?: boolean;
  renderMedia?: boolean;
}) => {
  const normalized = normalizeValue(value);
  const record = isRecord(normalized) && isRecord(normalized.output)
    ? normalized.output
    : normalized;

  if (isRecord(record)) {
    const imageUrls = renderMedia ? collectImageUrls(record) : [];
    const videoUrl = renderMedia ? collectVideoUrls(record)[0] || null : null;
    const summary = typeof record.summary === 'string' ? record.summary : null;
    const context = typeof record.context === 'string' ? record.context : null;
    const citations = Array.isArray(record.citations) ? record.citations : [];

    if (imageUrls.length > 0 || videoUrl || summary || context || citations.length > 0) {
      return (
        <div className="space-y-3">
          {imageUrls.length > 0 && (
            <div className="debug-media-grid">
              {imageUrls.map((url) => (
                <img key={url} src={url} alt="generated" className="debug-generated-image" />
              ))}
            </div>
          )}
          {videoUrl && (
            <video src={videoUrl} controls className="debug-generated-video" />
          )}
          {(summary || context) && (
            <DebugValue value={summary || context} preferOutputText />
          )}
          {citations.length > 0 && (
            <div className="debug-citation-list">
              {citations.map((citation, index) => (
                <Tag key={`${String(citation)}-${index}`}>{String(citation)}</Tag>
              ))}
            </div>
          )}
          <DebugValue value={record} preferOutputText={preferOutputText} />
        </div>
      );
    }
  }

  return <DebugValue value={value} preferOutputText={preferOutputText} />;
};

const DebugDrawer = ({ open, workflowId, totalNodeCount, onClose }: DebugDrawerProps) => {
  const [inputData, setInputData] = useState('');
  const [executing, setExecuting] = useState(false);
  const [executionResult, setExecutionResult] = useState<ExecutionResponse | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [nodeStatusMap, setNodeStatusMap] = useState<Map<string, NodeResult>>(new Map());
  const latestExecutionRequestRef = useRef(0);
  const resumingRef = useRef(false);
  const setRunStatus = useWorkflowStore((state) => state.setRunStatus);

  const addLog = (message: string) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs((prev) => [...prev, `[${timestamp}] ${message}`]);
  };

  const normalizeNodeResult = (nodeResult: ExecutionNodeResult): NodeResult => {
    const input = normalizeValue(nodeResult.input);
    const output = normalizeValue(nodeResult.output);

    return {
      nodeId: nodeResult.nodeId,
      nodeName: nodeResult.nodeName,
      status: nodeResult.status,
      input: isRecord(input) ? input : {},
      output: isRecord(output) ? output : {},
      duration: nodeResult.duration || 0,
      error: nodeResult.error,
    };
  };

  const extractInputText = (inputData: unknown) => {
    const normalizedInput = normalizeValue(inputData);
    if (typeof normalizedInput === 'string') {
      return normalizedInput;
    }
    if (isRecord(normalizedInput) && typeof normalizedInput.input === 'string') {
      return normalizedInput.input;
    }
    return '';
  };

  const toExecutionResult = (result: PersistedExecutionResponse) => {
    const restoredNodeResults = (result.nodeResults || []).map(normalizeNodeResult);
    return {
      restoredNodeResults,
      restoredResult: {
        executionId: result.executionId,
        status: result.status,
        nodeResults: restoredNodeResults,
        outputData: normalizeValue(result.outputData),
        duration: result.duration || 0,
        errorMessage: result.errorMessage,
      } as ExecutionResponse,
    };
  };

  const restoreExecution = (result: PersistedExecutionResponse) => {
    const { restoredNodeResults, restoredResult } = toExecutionResult(result);
    setInputData(extractInputText(result.inputData));
    setExecutionResult(restoredResult);
    setNodeStatusMap(new Map(restoredNodeResults.map((node) => [node.nodeId, node])));

    const restoredLogs = [
      `已加载最近一次执行记录 #${result.executionId}`,
      ...restoredNodeResults.flatMap((node) => {
        const nodeLog = node.status === 'FAILED'
          ? `❌ 节点 [${node.nodeName}] 执行失败${node.error ? `: ${node.error}` : ''}`
          : `✅ 节点 [${node.nodeName}] 执行成功,耗时 ${formatDuration(node.duration)}`;
        const toolLogs = formatToolTraceLogs(node.output);
        return [nodeLog, ...toolLogs];
      }),
      `${result.status === 'SUCCESS' ? '✅' : '❌'} 工作流执行${result.status === 'SUCCESS' ? '成功' : '失败'},总耗时 ${formatDuration(result.duration)}`,
    ];

    setLogs(restoredLogs.map((message) => `[历史] ${message}`));
  };

  const applyExecutionResult = (result: PersistedExecutionResponse) => {
    const { restoredNodeResults, restoredResult } = toExecutionResult(result);
    setExecutionResult(restoredResult);
    setNodeStatusMap(new Map(restoredNodeResults.map((node) => [node.nodeId, node])));
  };

  const syncLatestExecutionResult = async (targetWorkflowId: number) => {
    try {
      const result = await getLatestExecution(targetWorkflowId);
      if (result.code === 200 && result.data) {
        applyExecutionResult(result.data);
      }
    } catch (error) {
      console.warn('同步最近一次执行记录失败:', error);
    }
  };

  const addProgressLog = (event: ExecutionEvent) => {
    addLog(`📊 ${event.message}`);
    if (isRecord(event.data)) {
      const toolTraceLog = formatToolTraceLog(event.data);
      if (toolTraceLog) {
        addLog(toolTraceLog);
      }
    }
  };

  const addNodeSuccessLog = (node: NodeResult) => {
    addLog(`✅ 节点 [${node.nodeName}] 执行成功,耗时 ${formatDuration(node.duration)}`);
    formatToolTraceLogs(node.output).forEach(addLog);
  };

  const resetExecutionState = () => {
    setInputData('');
    setExecutionResult(null);
    setLogs([]);
    setNodeStatusMap(new Map());
  };

  const loadLatestExecution = async () => {
    const targetWorkflowId = workflowId;
    const requestId = ++latestExecutionRequestRef.current;

    if (!targetWorkflowId || executing) {
      return;
    }

    try {
      const result = await getLatestExecution(targetWorkflowId);
      if (latestExecutionRequestRef.current !== requestId) {
        return;
      }
      if (result.code === 200 && result.data) {
        restoreExecution(result.data);
      } else if (result.code === 200) {
        resetExecutionState();
        setLogs([`[历史] 当前工作流 #${targetWorkflowId} 暂无执行记录`]);
      }
    } catch (error) {
      if (latestExecutionRequestRef.current !== requestId) {
        return;
      }
      console.warn('加载最近一次执行记录失败:', error);
    }
  };

  useEffect(() => {
    latestExecutionRequestRef.current += 1;
    resetExecutionState();
  }, [workflowId]);

  // 把各节点的运行状态同步到画布：运行中 / 成功 / 失败的节点会被高亮；切换工作流或关闭面板时清掉
  useEffect(() => {
    const results = executionResult ? executionResult.nodeResults : Array.from(nodeStatusMap.values());
    const next: Record<string, NodeRunStatus> = {};
    results.forEach((node) => {
      next[node.nodeId] = node.status;
    });
    setRunStatus(next);
  }, [executionResult, nodeStatusMap, setRunStatus]);

  useEffect(() => () => setRunStatus({}), [workflowId, setRunStatus]);

  useEffect(() => {
    if (!open || executing) {
      return;
    }

    loadLatestExecution();
  }, [open, workflowId]);

  const handleExecute = async () => {
    if (!inputData.trim()) {
      addLog('❌ 错误: 输入数据不能为空');
      return;
    }

    if (!workflowId) {
      addLog('❌ 错误: 请先保存工作流');
      return;
    }

    setExecuting(true);
    setExecutionResult(null);
    setLogs([]);
    setNodeStatusMap(new Map());
    addLog('🚀 开始执行工作流...');

    try {
      const nodeResults: NodeResult[] = [];
      const tempNodeStatusMap = new Map<string, NodeResult>();
      let activeExecutionId = 0;
      
      await executeWorkflowStream(
        workflowId,
        inputData,
        (event: ExecutionEvent) => {
          console.log('收到事件:', event);
          
          switch (event.eventType) {
            case 'WORKFLOW_START':
              if (typeof event.data === 'number') {
                activeExecutionId = event.data;
              } else if (typeof event.data === 'string') {
                const parsedExecutionId = Number.parseInt(event.data, 10);
                activeExecutionId = Number.isNaN(parsedExecutionId) ? 0 : parsedExecutionId;
              }
              addLog('🚀 工作流开始执行');
              break;
              
            case 'NODE_START':
              addLog(`📍 节点 [${event.nodeName}] 开始执行...`);
              if (event.nodeId && event.nodeName) {
                const nodeResult: NodeResult = {
                  nodeId: event.nodeId,
                  nodeName: event.nodeName,
                  status: 'RUNNING',
                  input: {},
                  output: {},
                  duration: 0
                };
                tempNodeStatusMap.set(event.nodeId, nodeResult);
                setNodeStatusMap(new Map(tempNodeStatusMap));
              }
              break;
              
            case 'NODE_SUCCESS':
              if (event.nodeId && event.nodeName) {
                const duration = event.message?.match(/耗时 (\d+)ms/)?.[1] || '0';
                
                const eventData = isRecord(event.data) ? event.data : {};
                const nodeResult: NodeResult = {
                  nodeId: event.nodeId,
                  nodeName: event.nodeName,
                  status: 'SUCCESS',
                  input: isRecord(eventData.input) ? eventData.input : {},
                  output: isRecord(eventData.output) ? eventData.output : isRecord(event.data) ? event.data : {},
                  duration: parseInt(duration)
                };
                addNodeSuccessLog(nodeResult);
                tempNodeStatusMap.set(event.nodeId, nodeResult);
                nodeResults.push(nodeResult);
                setNodeStatusMap(new Map(tempNodeStatusMap));
              }
              break;
              
            case 'RECOVERY':
              addLog(`🔌 ${event.message}`);
              break;

            case 'NODE_PROGRESS':
              if (event.nodeId && event.message) {
                addProgressLog(event);
                const existingNode = tempNodeStatusMap.get(event.nodeId);
                if (existingNode) {
                  existingNode.status = 'RUNNING';
                  setNodeStatusMap(new Map(tempNodeStatusMap));
                }
              }
              break;
              
            case 'NODE_ERROR':
              if (event.nodeId && event.nodeName) {
                addLog(`❌ 节点 [${event.nodeName}] 执行失败: ${event.message}`);
                const nodeResult: NodeResult = {
                  nodeId: event.nodeId,
                  nodeName: event.nodeName,
                  status: 'FAILED',
                  input: {},
                  output: {},
                  duration: 0,
                  error: event.message
                };
                tempNodeStatusMap.set(event.nodeId, nodeResult);
                nodeResults.push(nodeResult);
                setNodeStatusMap(new Map(tempNodeStatusMap));
              }
              break;
              
            case 'WORKFLOW_COMPLETE': {
              const totalDuration = event.message?.match(/总耗时 (\d+)ms/)?.[1] || '0';
              addLog(`${event.status === 'SUCCESS' ? '✅' : '❌'} 工作流执行${event.status === 'SUCCESS' ? '成功' : '失败'},总耗时 ${formatDuration(totalDuration)}`);
              
              setExecutionResult({
                executionId: activeExecutionId,
                status: event.status as 'SUCCESS' | 'FAILED',
                nodeResults: Array.from(tempNodeStatusMap.values()),
                outputData: event.data || {},
                duration: parseInt(totalDuration),
                errorMessage: event.status === 'FAILED' ? event.message : undefined
              });
              if (event.status === 'FAILED' && activeExecutionId === 0) {
                syncLatestExecutionResult(workflowId);
              }
              break;
            }
          }
        },
        () => {
          setExecuting(false);
        },
        (error: Error) => {
          const errorMsg = error.message.includes('连接失败') 
            ? '连接失败,请检查后端服务是否运行或重新登录' 
            : error.message;
          addLog(`❌ 执行异常: ${errorMsg}`);
          setExecuting(false);
        }
      );
    } catch (error) {
      addLog(`❌ 执行异常: ${error instanceof Error ? error.message : '未知错误'}`);
      setExecuting(false);
    }
  };

  const handleResume = async (request: ResumeExecutionRequest = {}) => {
    if (resumingRef.current) {
      return; // 上一次续跑还没返回，忽略重复点击
    }
    if (!workflowId || !executionResult?.executionId) {
      addLog('❌ 错误: 缺少可恢复的执行记录');
      return;
    }

    resumingRef.current = true;
    const failedNode = request.startNodeId
      ? undefined
      : executionResult.nodeResults.find((node) => node.status === 'FAILED');
    setExecuting(true);
    addLog(`🔁 从执行记录 #${executionResult.executionId} 的失败断点继续执行...`);
    if (failedNode) {
      addLog(`📍 节点 [${failedNode.nodeName}] 重新执行...`);
      setExecutionResult((current) => current
        ? {
            ...current,
            status: 'RUNNING',
            nodeResults: current.nodeResults.map((node) => (
              node.nodeId === failedNode.nodeId
                ? { ...node, status: 'RUNNING', error: undefined }
                : node
            )),
          }
        : current);
      setNodeStatusMap((current) => {
        const next = new Map(current);
        const currentFailedNode = next.get(failedNode.nodeId);
        if (currentFailedNode) {
          next.set(failedNode.nodeId, {
            ...currentFailedNode,
            status: 'RUNNING',
            error: undefined,
          });
        }
        return next;
      });
    }

    try {
      const result = await resumeWorkflowExecution(workflowId, executionResult.executionId, {
        skipSuccessNodes: true,
        useSnapshotVariables: true,
        ...request,
      });
      if (result.code !== 200) {
        throw new Error(describeResumeFailure(result.code, result.message));
      }

      applyExecutionResult(result.data);
      const retriedFailedNode = (result.data.nodeResults || []).find((node) => node.status === 'FAILED');
      if (result.data.status === 'SUCCESS') {
        addLog(`✅ 断点续执行完成,状态: SUCCESS`);
      } else {
        addLog(`❌ 断点续执行完成,状态: FAILED`);
        if (retriedFailedNode?.error) {
          addLog(`❌ 节点 [${retriedFailedNode.nodeName}] 仍然失败: ${retriedFailedNode.error}`);
        }
      }
    } catch (error) {
      addLog(`❌ 断点续执行异常: ${error instanceof Error ? error.message : '未知错误'}`);
      // 乐观地把失败节点标成了"运行中"，失败后以服务端记录为准把状态改回来
      await syncLatestExecutionResult(workflowId);
    } finally {
      resumingRef.current = false;
      setExecuting(false);
    }
  };

  const getProgress = () => {
    const total = getProgressTotal();
    if (total === 0) return 0;
    const completed = getCompletedNodeCount();
    return Math.round((completed / total) * 100);
  };

  const getProgressTotal = () => {
    if (executionResult) {
      return executionResult.nodeResults.length;
    }
    return totalNodeCount > 0 ? totalNodeCount : nodeStatusMap.size;
  };

  const getCompletedNodeCount = () => (
    currentNodeResults.filter((r) => r.status === 'SUCCESS').length
  );

  const getAggregateUsage = () => {
    let inputTokens = 0;
    let outputTokens = 0;
    let totalTokens = 0;
    const costsByLabel = new Map<string, { currency: 'CNY' | 'USD'; cost: number }>();
    let hasUnpriced = false;

    currentNodeResults.forEach((node) => {
      const usage = getTokenUsage(node.output);
      if (!usage) return;
      inputTokens += usage.inputTokens;
      outputTokens += usage.outputTokens;
      totalTokens += usage.totalTokens;
      const estimate = estimateLlmCost(usage.model || usage.provider, usage.inputTokens, usage.outputTokens);
      if (estimate) {
        const existing = costsByLabel.get(estimate.currency) || { currency: estimate.currency, cost: 0 };
        existing.cost += estimate.cost;
        costsByLabel.set(estimate.currency, existing);
      } else {
        hasUnpriced = true;
      }
    });

    if (totalTokens === 0) {
      return null;
    }

    return { inputTokens, outputTokens, totalTokens, costs: Array.from(costsByLabel.values()), hasUnpriced };
  };

  const renderNodeResultItem = (nodeResult: NodeResult) => {
    let statusColor = 'default';
    let statusIcon = <LoadingOutlined />;
    
    if (nodeResult.status === 'SUCCESS') {
      statusColor = 'success';
      statusIcon = <CheckCircleOutlined />;
    } else if (nodeResult.status === 'FAILED') {
      statusColor = 'error';
      statusIcon = <CloseCircleOutlined />;
    } else if (nodeResult.status === 'RUNNING') {
      statusColor = 'processing';
      statusIcon = <LoadingOutlined />;
    }

    const tokenUsage = getTokenUsage(nodeResult.output);

    return {
      key: nodeResult.nodeId,
      label: (
        <div className="debug-node-collapse-label">
          <span className="debug-node-name">
            {statusIcon} {nodeResult.nodeName}
          </span>
          <span className="debug-node-tags">
            {tokenUsage && <TokenUsageTag usage={tokenUsage} />}
            <Tag color={statusColor}>{formatDuration(nodeResult.duration)}</Tag>
          </span>
        </div>
      ),
      children: (
        <div className="debug-node-result-body">
          {tokenUsage && (
            <Alert
              type="info"
              showIcon
              className="debug-token-alert"
              message={`Token 用量：输入 ${tokenUsage.inputTokens} / 输出 ${tokenUsage.outputTokens} / 合计 ${tokenUsage.totalTokens}`}
              description={(() => {
                const estimate = estimateLlmCost(tokenUsage.model || tokenUsage.provider, tokenUsage.inputTokens, tokenUsage.outputTokens);
                return estimate
                  ? `按 ${estimate.label} 参考单价估算，本次调用约花费 ${formatCost(estimate)}（非实时官方价格，仅供参考）`
                  : `模型 ${tokenUsage.model || tokenUsage.provider || '未知'} 暂无参考单价，无法估算费用`;
              })()}
            />
          )}
          <div>
            <div className="debug-field-label">输入数据</div>
            <DebugValue value={nodeResult.input} preferOutputText />
          </div>
          <div>
            <div className="debug-field-label">输出数据</div>
            <RichOutput value={nodeResult.output} preferOutputText />
          </div>
          {nodeResult.error && (
            <Alert message="错误信息" description={nodeResult.error} type="error" showIcon />
          )}
        </div>
      ),
    };
  };

  const currentNodeResults = executionResult 
    ? executionResult.nodeResults 
    : Array.from(nodeStatusMap.values());

  return (
    <Drawer
      title="调试面板"
      placement="right"
      onClose={onClose}
      open={open}
      width={520}
      className="debug-drawer"
      styles={{ body: { padding: 0 } }}
    >
      <div className="debug-drawer-content">
        <section className="debug-panel-section">
          <div className="debug-section-header">
            <div>
              <div className="debug-section-kicker">Test input</div>
              <h3>输入测试文本</h3>
            </div>
          </div>
          <TextArea
            rows={4}
            placeholder="请输入测试文本,例如: 人工智能的未来发展"
            value={inputData}
            onChange={(e) => setInputData(e.target.value)}
            disabled={executing}
            className="debug-input-area"
          />
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            onClick={handleExecute}
            loading={executing}
            block
            className="debug-run-button"
          >
            {executing ? '执行中...' : '执行工作流'}
          </Button>
        </section>

        {(executing || executionResult) && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Run status</div>
                <h3>执行状态</h3>
              </div>
              {executionResult && <span className="debug-duration">{formatDuration(executionResult.duration)}</span>}
            </div>
            <div className="debug-status-card">
              {executing && !executionResult && (
                <div className="flex items-center gap-2">
                  <LoadingOutlined className="text-blue-500" />
                  <span>执行中...</span>
                </div>
              )}
              {(executionResult || nodeStatusMap.size > 0) && (
                <>
                  <div className="flex items-center justify-between mb-2">
                    <span>
                      状态:{' '}
                      <Tag color={executionResult?.status === 'SUCCESS' ? 'success' : executionResult?.status === 'FAILED' ? 'error' : 'processing'}>
                        {executionResult?.status === 'SUCCESS' ? '成功' : executionResult?.status === 'FAILED' ? '失败' : '执行中'}
                      </Tag>
                    </span>
                  </div>
                  <Progress 
                    percent={getProgress()} 
                    status={executionResult?.status === 'SUCCESS' ? 'success' : executionResult?.status === 'FAILED' ? 'exception' : 'active'} 
                  />
                  <div className="mt-2 text-sm text-gray-600">
                    已完成节点: {getCompletedNodeCount()} / {getProgressTotal()}
                  </div>
                  {(() => {
                    const aggregate = getAggregateUsage();
                    if (!aggregate) return null;
                    const costText = aggregate.costs
                      .map((c) => formatCost({ label: '', currency: c.currency, cost: c.cost }))
                      .join(' + ');
                    return (
                      <div className="debug-aggregate-usage">
                        本次运行共消耗 {aggregate.totalTokens} tokens（输入 {aggregate.inputTokens} / 输出 {aggregate.outputTokens}）
                        {costText && <> ，参考费用约 {costText}</>}
                        {aggregate.hasUnpriced && <span className="debug-muted">（含未收录单价的模型，未计入费用）</span>}
                      </div>
                    );
                  })()}
                </>
              )}
            </div>
          </section>
        )}

        {executionResult?.status === 'FAILED' && executionResult.executionId > 0 && workflowId && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Resume</div>
                <h3>断点续跑</h3>
              </div>
            </div>
            <ResumePanel
              failedNodes={currentNodeResults.filter((n) => n.status === 'FAILED').map((n) => ({ nodeId: n.nodeId, nodeName: n.nodeName, error: n.error }))}
              allNodes={currentNodeResults.map((n) => ({ nodeId: n.nodeId, nodeName: n.nodeName }))}
              loading={executing}
              onResume={handleResume}
            />
          </section>
        )}

        {workflowId && executionResult && executionResult.executionId > 0 && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Timeline</div>
                <h3>执行时间线</h3>
              </div>
            </div>
            <ExecutionTimeline
              workflowId={workflowId}
              executionId={executionResult.executionId}
              refreshKey={`${executionResult.status}-${executing}`}
            />
          </section>
        )}

        {workflowId && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Engines</div>
                <h3>双引擎一致性对比</h3>
              </div>
            </div>
            <EngineCompare workflowId={workflowId} inputData={inputData} disabled={executing} />
          </section>
        )}

        {currentNodeResults.length > 0 && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Nodes</div>
                <h3>节点执行结果</h3>
              </div>
              <span className="debug-count-pill">{currentNodeResults.length}</span>
            </div>
            <Collapse
              items={currentNodeResults.map(renderNodeResultItem)}
              defaultActiveKey={currentNodeResults.map((r) => r.nodeId)}
              bordered={false}
              ghost
              className="debug-result-collapse"
            />
          </section>
        )}

        {executionResult && executionResult.status === 'SUCCESS' && (
          <section className="debug-panel-section">
            <div className="debug-section-header">
              <div>
                <div className="debug-section-kicker">Final</div>
                <h3>最终输出</h3>
              </div>
            </div>
            <div className="debug-final-output">
              {(() => {
                const imageUrls = collectImageUrls(executionResult.outputData);
                const videoUrls = collectVideoUrls(executionResult.outputData);
                if (imageUrls.length > 0 || videoUrls.length > 0) {
                  return (
                    <div className="debug-final-media">
                      {imageUrls.length > 0 && (
                        <div className="debug-media-grid">
                          {imageUrls.map((url) => (
                            <a key={url} href={url} target="_blank" rel="noreferrer" className="debug-image-link">
                              <img src={url} alt="workflow output" className="debug-generated-image" />
                            </a>
                          ))}
                        </div>
                      )}
                      {videoUrls.map((url) => (
                        <video key={url} src={url} controls className="debug-generated-video" />
                      ))}
                    </div>
                  );
                }

                const audioOutput = collectAudioOutput(executionResult.outputData);
                if (audioOutput) {
                  return (
                    <AudioPlayer 
                      audioUrl={audioOutput.audioUrl}
                      fileName={audioOutput.fileName}
                    />
                  );
                }
                
                return (
                  <RichOutput value={executionResult.outputData} preferOutputText />
                );
              })()}
            </div>
          </section>
        )}

        <section className="debug-panel-section">
          <div className="debug-section-header">
            <div>
              <div className="debug-section-kicker">Logs</div>
              <h3>执行日志</h3>
            </div>
          </div>
          <div className="debug-log-list">
            {logs.map((log, index) => (
              <div
                key={index}
                className={`debug-log-line ${log.includes('❌') ? 'is-error' : log.includes('✅') ? 'is-success' : ''}`}
              >
                {log}
              </div>
            ))}
            {logs.length === 0 && (
              <div className="debug-empty-log">暂无日志</div>
            )}
          </div>
        </section>
      </div>
    </Drawer>
  );
};

export default DebugDrawer;
