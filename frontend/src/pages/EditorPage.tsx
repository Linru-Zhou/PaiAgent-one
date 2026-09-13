import { useState, useEffect, useRef, useCallback } from 'react';
import { Alert, Button, Input, Form, message, Checkbox, Select, Modal, List, Popconfirm } from 'antd';
import { SaveOutlined, FolderOpenOutlined, BugOutlined, LogoutOutlined, PlusOutlined, DeleteOutlined, DatabaseOutlined, ApiOutlined } from '@ant-design/icons';
import { Edge, MarkerType, Node } from '@xyflow/react';
import NodePanel from '../components/NodePanel';
import FlowCanvas from '../components/FlowCanvas';
import DebugDrawer from '../components/DebugDrawer';
import SkillSelector from '../components/SkillSelector';
import LLMConfigModal from '../components/LLMConfigModal';
import { logout } from '../api/auth';
import { useWorkflowStore } from '../store/workflowStore';
import { useAuthStore } from '../store/authStore';
import { useLLMConfigStore } from '../store/llmConfigStore';
import { createWorkflow, updateWorkflow, executeWorkflow, getWorkflows, getWorkflow, deleteWorkflow, Workflow } from '../api/workflow';
import { getKnowledgeBases, KnowledgeBase } from '../api/knowledge';
import { getMcpTools, McpToolConfig } from '../api/mcpTools';
import { getRefreshToken } from '../utils/auth';
import {
  getProviderFromNodeType,
  getProviderLabel,
  getSupportedProviderOptions,
  isLlmNodeType,
  normalizeProviderKey,
} from '../utils/provider';
import { createDefaultWorkflowNodes, normalizeWorkflowNodes, serializeWorkflowNodes } from '../utils/workflowNode';
import { useNavigate, useParams } from 'react-router-dom';

interface OutputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface LlmInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface LlmOutputParam {
  name: string;
  type: string;
  description?: string;
}

interface TtsInputParam {
  name: string;
  type: 'input' | 'reference';
  value: string;
  referenceNode?: string;
}

interface TtsOutputParam {
  name: string;
  value: string;
}

interface ConditionRule {
  id: string;
  field: string;
  operator: string;
  value: string;
}

const CONDITION_OPERATORS: { value: string; label: string; needsValue: boolean }[] = [
  { value: 'eq', label: '等于', needsValue: true },
  { value: 'neq', label: '不等于', needsValue: true },
  { value: 'gt', label: '大于', needsValue: true },
  { value: 'gte', label: '大于等于', needsValue: true },
  { value: 'lt', label: '小于', needsValue: true },
  { value: 'lte', label: '小于等于', needsValue: true },
  { value: 'contains', label: '包含', needsValue: true },
  { value: 'notContains', label: '不包含', needsValue: true },
  { value: 'startsWith', label: '以...开头', needsValue: true },
  { value: 'endsWith', label: '以...结尾', needsValue: true },
  { value: 'isEmpty', label: '为空', needsValue: false },
  { value: 'isNotEmpty', label: '不为空', needsValue: false },
];

interface WorkflowCanvasData {
  nodes?: Node[];
  edges?: Edge[];
}

const normalizeAgentToolSelection = (tools: string[]) => {
  const normalized = new Set<string>();
  tools.forEach((tool) => {
    if (tool === 'memory_retrieve') return;
    if (tool === 'web_search' || tool === 'web_fetch') return;
    normalized.add(tool);
  });
  return Array.from(normalized);
};

const expandAgentToolSelection = (tools: string[]) => {
  const expanded = new Set<string>();
  tools.forEach((tool) => {
    if (tool === 'memory_retrieve') return;
    expanded.add(tool);
    if (tool === 'web_search') {
      expanded.add('web_fetch');
    }
  });
  return Array.from(expanded);
};

const normalizeStringArray = (value: unknown) => {
  if (!Array.isArray(value)) return [];
  return value.map(String).filter(Boolean);
};

const enrichKnowledgeBaseNames = (nodes: Node[], knowledgeBases: KnowledgeBase[]) => {
  if (knowledgeBases.length === 0) return nodes;
  const nameById = new Map(knowledgeBases.map((base) => [String(base.id), base.name]));
  let changed = false;
  const nextNodes = nodes.map((node) => {
    const knowledgeBaseId = node.data?.knowledgeBaseId;
    if (typeof knowledgeBaseId !== 'string') return node;
    const knowledgeBaseName = nameById.get(knowledgeBaseId);
    if (!knowledgeBaseName || node.data?.knowledgeBaseName === knowledgeBaseName) return node;
    changed = true;
    return {
      ...node,
      data: {
        ...node.data,
        knowledgeBaseName,
      },
    };
  });
  return changed ? nextNodes : nodes;
};

/**
 * 工作流编辑器页面
 */
const EditorPage = () => {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const { username, clearAuth } = useAuthStore();
  const { nodes, edges, currentWorkflowId, setCurrentWorkflowId, selectedNode, setNodes, setEdges } = useWorkflowStore();
  const [workflowName, setWorkflowName] = useState('未命名工作流');
  const [engineType, setEngineType] = useState('dag');
  const [saving, setSaving] = useState(false);
  const [debugDrawerOpen, setDebugDrawerOpen] = useState(false);
  const [outputParams, setOutputParams] = useState<OutputParam[]>([]);
  const [responseContent, setResponseContent] = useState('');
  const [loadModalOpen, setLoadModalOpen] = useState(false);
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loadingWorkflows, setLoadingWorkflows] = useState(false);
  const [deletingWorkflowId, setDeletingWorkflowId] = useState<number | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [mcpTools, setMcpTools] = useState<McpToolConfig[]>([]);
  const hasLoadedRef = useRef<number | null>(null);
  const routeWorkflowId = id ? Number.parseInt(id, 10) : null;
  const activeWorkflowId = Number.isFinite(routeWorkflowId) ? routeWorkflowId : currentWorkflowId;
  
  // LLM 节点配置状态
  const [llmConfig, setLlmConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    temperature: 0.7,
    prompt: '',
    skillName: '',
    maxSteps: 5,
    agentStrategy: 'none',
    tools: [] as string[],
    memoryEnabled: false,
    memoryTopK: 5,
    mcpToolIds: [] as string[],
    knowledgeBaseId: undefined as string | undefined,
    knowledgeBaseName: '',
    knowledgeTopK: 5,
    knowledgeScoreThreshold: 0.2
  });
  const [llmInputParams, setLlmInputParams] = useState<LlmInputParam[]>([]);
  const [llmOutputParams, setLlmOutputParams] = useState<LlmOutputParam[]>([]);

  // LLM 全局配置 Store
  const { configs: llmGlobalConfigs, fetchAllConfigs: fetchLLMGlobalConfigs } = useLLMConfigStore();
  const providerOptions = getSupportedProviderOptions();

  const buildRuntimeToolSelection = (tools: string[], mcpToolIds: string[]) => {
    const selected = new Set(expandAgentToolSelection(tools));
    mcpTools
      .filter((tool) => mcpToolIds.includes(String(tool.id)))
      .forEach((tool) => {
        if (tool.toolName) {
          selected.add(tool.toolName);
        }
        if (tool.toolName === 'web_search') {
          selected.add('web_fetch');
        }
      });
    return Array.from(selected);
  };

  // TTS 节点配置状态
  const [ttsConfig, setTtsConfig] = useState({
    provider: '',
    configId: undefined as number | undefined,
    apiUrl: '',
    apiKey: '',
    model: '',
    voice: '',
    languageType: 'Auto',
    instruction: '',
    speed: 1,
    volume: 1,
    sampleRate: 24000
  });
  const [ttsInputParams, setTtsInputParams] = useState<TtsInputParam[]>([]);
  const [ttsOutputParams, setTtsOutputParams] = useState<TtsOutputParam[]>([]);

  // 自动保存定时器
  const autoSaveTimerRef = useRef<number | null>(null);

  const resolveSelectedNodeProvider = (node: Node | null) => {
    if (!node) {
      return '';
    }

    const configuredProvider = normalizeProviderKey(String(node.data?.provider || ''));
    if (configuredProvider) {
      return configuredProvider;
    }

    return getProviderFromNodeType(String(node.data?.type || ''));
  };

  // 处理节点拖拽开始
  const handleDragStart = (event: React.DragEvent, nodeType: string, displayName: string) => {
    event.dataTransfer.setData('application/reactflow-type', nodeType);
    event.dataTransfer.setData('application/reactflow-label', displayName);
    event.dataTransfer.effectAllowed = 'move';
  };

  // 处理节点点击
  const handleNodeClick = (node: Node) => {
    console.log('Node clicked:', node);

    // 清理之前的自动保存定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
      autoSaveTimerRef.current = null;
    }

    useWorkflowStore.getState().setSelectedNode(node);

    // 加载节点配置
    if (node.data?.type === 'output') {
      setOutputParams((node.data?.outputParams as OutputParam[]) || []);
      setResponseContent((node.data?.responseContent as string) || '');
    } else if (isLlmNodeType(String(node.data?.type || ''))) {
      // 加载 LLM 节点配置
      const configId = (node.data?.configId as number) || undefined;
      const matchedGlobalConfig = configId
        ? llmGlobalConfigs.find(c => c.id === configId)
        : undefined;
      const provider = normalizeProviderKey(
        matchedGlobalConfig?.provider ||
        String(node.data?.provider || '') ||
        getProviderFromNodeType(String(node.data?.type || ''))
      );
      setLlmConfig({
        provider,
        configId,
        apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
        apiKey: configId ? '' : (node.data?.apiKey as string) || '',
        model: matchedGlobalConfig?.model || (node.data?.model as string) || '',
        temperature: (node.data?.temperature as number) || 0.7,
        prompt: (node.data?.prompt as string) || '',
        skillName: (node.data?.skillName as string) || '',
        maxSteps: (node.data?.maxSteps as number) || 5,
        agentStrategy: (node.data?.agentStrategy as string) || (node.data?.type === 'react_agent' ? 'react' : 'none'),
        tools: normalizeAgentToolSelection(
          Array.isArray(node.data?.tools) ? node.data.tools as string[] : []
        ),
        memoryEnabled: Boolean(node.data?.memoryEnabled),
        memoryTopK: (node.data?.memoryTopK as number) || 5,
        mcpToolIds: normalizeStringArray(node.data?.mcpToolIds),
        knowledgeBaseId: (node.data?.knowledgeBaseId as string) || undefined,
        knowledgeBaseName: (node.data?.knowledgeBaseName as string) || '',
        knowledgeTopK: (node.data?.knowledgeTopK as number) || 5,
        knowledgeScoreThreshold: (node.data?.knowledgeScoreThreshold as number) || 0.2
      });
      setLlmInputParams((node.data?.inputParams as LlmInputParam[]) || []);
      setLlmOutputParams((node.data?.outputParams as LlmOutputParam[]) || []);
    } else if (node.data?.type === 'tts') {
      // 加载 TTS 节点配置
      const configId = (node.data?.configId as number) || undefined;
      const matchedGlobalConfig = configId
        ? llmGlobalConfigs.find(c => c.id === configId)
        : undefined;
      const provider = normalizeProviderKey(
        matchedGlobalConfig?.provider ||
        String(node.data?.provider || '') ||
        (String(node.data?.model || '').toLowerCase().includes('step') ? 'step' : '')
      );
      setTtsConfig({
        provider,
        configId,
        apiUrl: matchedGlobalConfig?.apiUrl || (node.data?.apiUrl as string) || '',
        apiKey: configId ? '' : (node.data?.apiKey as string) || '',
        model: getTtsModelForProvider(provider, matchedGlobalConfig?.ttsModel, matchedGlobalConfig?.model || (node.data?.model as string)),
        voice: (node.data?.voice as string) || (provider === 'step' ? 'cixingnansheng' : provider === 'qwen' ? 'Cherry' : ''),
        languageType: (node.data?.languageType as string) || 'Auto',
        instruction: (node.data?.instruction as string) || '',
        speed: (node.data?.speed as number) || 1,
        volume: (node.data?.volume as number) || 1,
        sampleRate: (node.data?.sampleRate as number) || 24000
      });
      setTtsInputParams((node.data?.inputParams as TtsInputParam[]) || []);
      setTtsOutputParams((node.data?.outputParams as TtsOutputParam[]) || []);
    }
  };

  // 初始化加载 LLM 全局配置
  useEffect(() => {
    fetchLLMGlobalConfigs();
  }, [fetchLLMGlobalConfigs]);

  useEffect(() => {
    getKnowledgeBases()
      .then((result) => {
        if (result.code === 200) {
          setKnowledgeBases(result.data);
        }
      })
      .catch(() => {
        setKnowledgeBases([]);
      });
  }, []);

  useEffect(() => {
    getMcpTools()
      .then((result) => {
        if (result.code === 200) {
          setMcpTools(result.data);
        }
      })
      .catch(() => {
        setMcpTools([]);
      });
  }, []);

  // 当全局配置异步加载完成后，补齐当前选中节点的展示配置
  useEffect(() => {
    if (!selectedNode) return;
    const nodeType = selectedNode.data?.type;
    if (!isLlmNodeType(String(nodeType || ''))) return;
    if (!llmConfig.configId) return;

    const config = llmGlobalConfigs.find(c => c.id === llmConfig.configId);
    if (!config) return;

    const needsSync =
      llmConfig.apiUrl !== config.apiUrl ||
      llmConfig.model !== config.model ||
      llmConfig.apiKey !== '';

    if (needsSync) {
      setLlmConfig(prev => ({
        ...prev,
        provider: normalizeProviderKey(config.provider),
        apiUrl: config.apiUrl,
        apiKey: '',
        model: config.model
      }));
    }
  }, [llmGlobalConfigs, selectedNode, llmConfig]);

  // 当全局配置异步加载完成后，补齐当前选中 TTS 节点的展示配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'tts') return;
    if (!ttsConfig.configId) return;

    const config = llmGlobalConfigs.find(c => c.id === ttsConfig.configId);
    if (!config) return;
    const provider = normalizeProviderKey(config.provider);
    const model = getTtsModelForProvider(provider, config.ttsModel, config.model);

    const needsSync =
      ttsConfig.apiUrl !== config.apiUrl ||
      ttsConfig.model !== model ||
      ttsConfig.apiKey !== '';

    if (needsSync) {
      setTtsConfig(prev => ({
        ...prev,
        provider,
        apiUrl: config.apiUrl,
        apiKey: '',
        model
      }));
    }
  }, [llmGlobalConfigs, selectedNode, ttsConfig]);

  // 加载指定工作流
  const loadWorkflowById = useCallback(async (workflowId: number) => {
    try {
      const result = await getWorkflow(workflowId);
      if (result.code === 200) {
        const workflow = result.data;
        setWorkflowName(workflow.name);
        setEngineType(workflow.engineType || 'dag');
        setCurrentWorkflowId(workflow.id);
        
        const flowData = JSON.parse(workflow.flowData) as WorkflowCanvasData;
        console.log('加载的工作流数据:', flowData);
        
        // 加载节点
        const loadedNodes = enrichKnowledgeBaseNames(normalizeWorkflowNodes(flowData.nodes || []), knowledgeBases);
        setNodes(loadedNodes);
        
        // 加载连线并恢复箭头
        const loadedEdges = (flowData.edges || []).map((edge) => ({
          ...edge,
          markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 20,
            height: 20,
          },
        }));
        setEdges(loadedEdges);
        
        // 恢复输出节点配置
        const outputNode = loadedNodes.find((node) => node.data?.type === 'output');
        console.log('找到输出节点:', outputNode);
        console.log('输出节点配置 - outputParams:', outputNode?.data?.outputParams);
        console.log('输出节点配置 - responseContent:', outputNode?.data?.responseContent);

        const rawOutputParams = outputNode?.data?.outputParams;
        const rawResponseContent = outputNode?.data?.responseContent;
        setOutputParams(Array.isArray(rawOutputParams) ? rawOutputParams as OutputParam[] : []);
        setResponseContent(typeof rawResponseContent === 'string' ? rawResponseContent : '');
        
        message.success('工作流加载成功');
      }
    } catch {
      message.error('工作流加载失败');
    }
  }, [knowledgeBases, setCurrentWorkflowId, setEdges, setNodes]);

  useEffect(() => {
    const enrichedNodes = enrichKnowledgeBaseNames(nodes, knowledgeBases);
    if (enrichedNodes !== nodes) {
      setNodes(enrichedNodes);
    }
  }, [knowledgeBases, nodes, setNodes]);

  // 从 URL 加载工作流
  useEffect(() => {
    if (id) {
      const workflowId = parseInt(id);
      // 避免重复加载 - 使用 ref 标记
      if (hasLoadedRef.current !== workflowId) {
        hasLoadedRef.current = workflowId;
        loadWorkflowById(workflowId);
      }
    }
  }, [id, loadWorkflowById]);

  // 保存工作流
  const handleSave = async () => {
    if (nodes.length === 0) {
      message.warning('工作流为空,无法保存');
      return;
    }

    const flowData = JSON.stringify({
      nodes: serializeWorkflowNodes(nodes),
      edges: edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourceHandle: edge.sourceHandle,
        targetHandle: edge.targetHandle,
      })),
    });

    setSaving(true);
    try {
      if (currentWorkflowId) {
        // 更新
        await updateWorkflow(currentWorkflowId, {
          name: workflowName,
          flowData,
          engineType,
        });
        message.success('工作流保存成功');
      } else {
        // 创建
        const result = await createWorkflow({
          name: workflowName,
          description: '通过编辑器创建',
          flowData,
          engineType,
        });
        if (result.code === 200) {
          const workflowId = result.data.id;
          setCurrentWorkflowId(workflowId);
          // 更新 URL
          navigate(`/editor/${workflowId}`, { replace: true });
          message.success('工作流创建成功');
        }
      }
    } catch {
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  // 执行工作流(从调试抽屉调用)
  const handleExecute = async (inputData: string) => {
    if (!activeWorkflowId) {
      throw new Error('请先保存工作流');
    }

    const result = await executeWorkflow(activeWorkflowId, inputData);
    if (result.code === 200) {
      return result.data;
    } else {
      throw new Error(result.message || '执行失败');
    }
  };

  // 打开调试抽屉
  const handleOpenDebug = () => {
    if (!activeWorkflowId) {
      message.warning('请先保存工作流');
      return;
    }
    setDebugDrawerOpen(true);
  };

  // 登出
  const handleLogout = async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await logout({ refreshToken });
      } catch {
        // 后端退出失败不阻塞本地登出
      }
    }
    clearAuth();
    navigate('/login');
  };

  // 添加输出参数
  const handleAddOutputParam = () => {
    setOutputParams([...outputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除输出参数
  const handleRemoveOutputParam = (index: number) => {
    setOutputParams(outputParams.filter((_, i) => i !== index));
  };

  // 更新输出参数
  const handleUpdateOutputParam = (index: number, field: keyof OutputParam, value: string) => {
    const newParams = [...outputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setOutputParams(newParams);
  };

  // 获取可引用的节点列表：只允许引用当前节点的直接上游节点
  const getReferenceableNodes = () => {
    if (!selectedNode) return [];

    const sourceIds = Array.from(new Set(edges
      .filter((edge) => edge.target === selectedNode.id)
      .map((edge) => edge.source)));

    return sourceIds
      .map((sourceId) => nodes.find((node) => node.id === sourceId))
      .filter((node): node is Node => Boolean(node));
  };

  // 获取节点的输出参数
  const getNodeOutputParams = (nodeType: string): string[] => {
    switch (nodeType) {
      case 'input':
        return ['user_input'];
      case 'llm':
      case 'react_agent':
      case 'openai':
      case 'deepseek':
      case 'qwen':
      case 'step':
      case 'zhipu':
      case 'ai_ping':
        return nodeType === 'react_agent'
          ? ['output', 'finalAnswer', 'steps', 'tokens']
          : ['output', 'tokens'];
      case 'tts':
        return ['audioUrl', 'fileName', 'output'];
      case 'web_search':
        return ['summary', 'results', 'citations', 'output'];
      case 'web_fetch':
        return ['content', 'pages', 'citations', 'output'];
      case 'memory_write':
        return ['memoryId', 'scope', 'stored'];
      case 'memory_retrieve':
        return ['context', 'memories', 'citations', 'output'];
      case 'knowledge_upsert':
        return ['knowledgeBaseId', 'contentId', 'chunkCount', 'indexed', 'output'];
      case 'knowledge_retrieve':
        return ['context', 'chunks', 'citations', 'output'];
      case 'image_generate':
        return ['imageUrl', 'imageUrls', 'prompt', 'output'];
      case 'video_generate':
        return ['videoUrl', 'coverUrl', 'taskId', 'status', 'output'];
      default:
        return ['output'];
    }
  };

  const getConfiguredOutputParamNames = (node: Node) => {
    const nodeType = (node.data?.type as string) || '';
    if (nodeType === 'input') {
      return ['user_input'];
    }

    if (!Array.isArray(node.data?.outputParams)) {
      if (nodeType === 'image_generate' || nodeType === 'video_generate') {
        return getNodeOutputParams(nodeType);
      }
      return [];
    }

    return (node.data.outputParams as Array<{ name?: string }>)
      .map((param) => param.name?.trim())
      .filter((name): name is string => Boolean(name));
  };

  // 获取所有可引用的参数（节点.参数名格式）
  const getReferenceableParams = () => {
    const params: { label: string; value: string }[] = [];
    getReferenceableNodes().forEach(node => {
      const nodeLabel = (node.data?.label as string) || node.id;
      const outputParams = Array.from(new Set(getConfiguredOutputParamNames(node)));
      
      outputParams.forEach(param => {
        params.push({
          label: `${nodeLabel}.${param}`,
          value: `${node.id}.${param}`
        });
      });
    });
    return params;
  };

  // 保存输出节点配置
  const handleSaveOutputConfig = () => {
    if (!selectedNode) return;

    // 验证参数配置
    for (const param of outputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证回答内容配置中的参数引用
    const paramNames = new Set(outputParams.map(p => p.name));
    const templateParamRegex = /\{\{(\w+)\}\}/g;
    const matches = responseContent.matchAll(templateParamRegex);
    const undefinedParams: string[] = [];
    
    for (const match of matches) {
      const paramName = match[1];
      if (!paramNames.has(paramName)) {
        undefinedParams.push(paramName);
      }
    }
    
    if (undefinedParams.length > 0) {
      message.warning(`回答内容中引用了未定义的参数: ${undefinedParams.join(', ')}`);
      return;
    }

    // 保存到节点的 data 中
    const updatedData = {
      ...selectedNode.data,
      outputParams,
      responseContent
    };

    console.log('保存输出节点配置:', {
      nodeId: selectedNode.id,
      outputParams,
      responseContent,
      updatedData
    });

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    message.success('配置保存成功');
  };

  // 打开加载工作流对话框
  const handleOpenLoadModal = async () => {
    setLoadingWorkflows(true);
    setLoadModalOpen(true);
    try {
      const result = await getWorkflows();
      if (result.code === 200) {
        setWorkflows(result.data);
      }
    } catch {
      message.error('获取工作流列表失败');
    } finally {
      setLoadingWorkflows(false);
    }
  };

  // 加载选中的工作流
  const handleLoadWorkflow = (workflow: Workflow) => {
    setLoadModalOpen(false);
    navigate(`/editor/${workflow.id}`);
  };

  // 删除工作流
  const handleDeleteWorkflow = async (workflow: Workflow) => {
    setDeletingWorkflowId(workflow.id);
    try {
      const result = await deleteWorkflow(workflow.id);
      if (result.code !== 200) {
        message.error(result.message || '删除工作流失败');
        return;
      }

      setWorkflows((current) => current.filter((item) => item.id !== workflow.id));
      message.success('工作流已删除');

      if (activeWorkflowId === workflow.id) {
        hasLoadedRef.current = null;
        setCurrentWorkflowId(null);
        setWorkflowName('未命名工作流');
        setEngineType('dag');
        setOutputParams([]);
        setResponseContent('');
        setNodes(createDefaultWorkflowNodes());
        setEdges([]);
        navigate('/editor', { replace: true });
      }
    } catch {
      message.error('删除工作流失败');
    } finally {
      setDeletingWorkflowId(null);
    }
  };

  // 新建工作流
  const handleCreateNew = () => {
    setCurrentWorkflowId(null);
    setWorkflowName('未命名工作流');
    
    // 创建默认的输入和输出节点(上下排列)
    setNodes(createDefaultWorkflowNodes());
    setEdges([]);
    navigate('/editor');
    message.info('已创建新工作流');
  };

  // 保存 LLM 节点配置
  const handleSaveLlmConfig = () => {
    if (!selectedNode) return;

    // 验证输入参数
    for (const param of llmInputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证提示词
    if (!llmConfig.prompt) {
      message.warning('请填写提示词模板');
      return;
    }

    // 验证提示词中的参数引用
    const paramNames = new Set(llmInputParams.map(p => p.name));
    const templateParamRegex = /\{\{(\w+)\}\}/g;
    const matches = llmConfig.prompt.matchAll(templateParamRegex);
    const undefinedParams: string[] = [];

    for (const match of matches) {
      const paramName = match[1];
      if (!paramNames.has(paramName)) {
        undefinedParams.push(paramName);
      }
    }

    if (undefinedParams.length > 0) {
      message.warning(`提示词模板中引用了未定义的参数: ${undefinedParams.join(', ')}`);
      return;
    }

    // 如果没有选择全局配置，需要验证 API 配置
    if (!llmConfig.configId) {
      if (!llmConfig.provider) {
        message.warning('请选择供应商');
        return;
      }
      if (!llmConfig.apiUrl) {
        message.warning('请选择全局配置或填写 API 地址');
        return;
      }
      if (!llmConfig.apiKey) {
        message.warning('请选择全局配置或填写 API 密钥');
        return;
      }
      if (!llmConfig.model) {
        message.warning('请选择全局配置或填写模型名称');
        return;
      }
    }

    const useGlobalConfig = !!llmConfig.configId;
    const nextAgentStrategy = selectedNode.data?.type === 'react_agent' ? 'react' : llmConfig.agentStrategy;
    const selectedKnowledgeBaseName = llmConfig.knowledgeBaseId
      ? knowledgeBases.find((base) => String(base.id) === llmConfig.knowledgeBaseId)?.name || llmConfig.knowledgeBaseName
      : '';
    const updatedData = {
      ...selectedNode.data,
      provider: llmConfig.provider,
      configId: llmConfig.configId,
      apiUrl: useGlobalConfig ? '' : llmConfig.apiUrl,
      apiKey: useGlobalConfig ? '' : llmConfig.apiKey,
      model: useGlobalConfig ? '' : llmConfig.model,
      temperature: llmConfig.temperature,
      prompt: llmConfig.prompt,
      skillName: llmConfig.skillName,
      maxSteps: llmConfig.maxSteps,
      agentStrategy: nextAgentStrategy,
      tools: nextAgentStrategy === 'react' ? buildRuntimeToolSelection(llmConfig.tools, llmConfig.mcpToolIds) : [],
      memoryEnabled: llmConfig.memoryEnabled,
      memoryTopK: llmConfig.memoryTopK,
      mcpToolIds: nextAgentStrategy === 'react' ? llmConfig.mcpToolIds : [],
      knowledgeBaseId: llmConfig.knowledgeBaseId,
      knowledgeBaseName: selectedKnowledgeBaseName,
      knowledgeTopK: llmConfig.knowledgeTopK,
      knowledgeScoreThreshold: llmConfig.knowledgeScoreThreshold,
      inputParams: llmInputParams,
      outputParams: llmOutputParams
    };

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    message.success('配置保存成功');
  };

  // 添加 LLM 输入参数
  const handleAddLlmInputParam = () => {
    setLlmInputParams([...llmInputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除 LLM 输入参数
  const handleRemoveLlmInputParam = (index: number) => {
    setLlmInputParams(llmInputParams.filter((_, i) => i !== index));
  };

  // 更新 LLM 输入参数
  const handleUpdateLlmInputParam = (index: number, field: keyof LlmInputParam, value: string) => {
    const newParams = [...llmInputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmInputParams(newParams);
  };

  // 保存 TTS 节点配置
  const handleSaveTtsConfig = () => {
    if (!selectedNode) return;

    if (!ttsConfig.configId) {
      if (!ttsConfig.provider) {
        message.warning('请选择供应商');
        return;
      }
      if (!ttsConfig.apiUrl) {
        message.warning('请选择全局配置或填写 API 地址');
        return;
      }
      if (!ttsConfig.apiKey) {
        message.warning('请选择全局配置或填写 API Key');
        return;
      }
      if (!ttsConfig.model) {
        message.warning('请选择全局配置或填写模型名称');
        return;
      }
    }

    if (normalizeProviderKey(ttsConfig.provider) === 'step' && ttsConfig.instruction.length > 200) {
      message.warning('StepAudio 2.5 TTS 的 instruction 不能超过 200 字符');
      return;
    }

    // 验证输入参数
    for (const param of ttsInputParams) {
      if (!param.name) {
        message.warning('请填写所有参数名');
        return;
      }
      if (param.type === 'input' && !param.value) {
        message.warning('请填写输入值');
        return;
      }
      if (param.type === 'reference' && !param.referenceNode) {
        message.warning('请选择引用参数');
        return;
      }
    }

    // 验证输出参数
    for (const param of ttsOutputParams) {
      if (!param.name) {
        message.warning('请填写所有输出参数名');
        return;
      }
    }

    const useGlobalConfig = !!ttsConfig.configId;
    const updatedData = {
      ...selectedNode.data,
      provider: ttsConfig.provider,
      configId: ttsConfig.configId,
      apiUrl: useGlobalConfig ? '' : ttsConfig.apiUrl,
      apiKey: useGlobalConfig ? '' : ttsConfig.apiKey,
      model: useGlobalConfig ? '' : ttsConfig.model,
      voice: ttsConfig.voice,
      languageType: ttsConfig.languageType,
      instruction: ttsConfig.instruction,
      speed: ttsConfig.speed,
      volume: ttsConfig.volume,
      sampleRate: ttsConfig.sampleRate,
      inputParams: ttsInputParams,
      outputParams: ttsOutputParams
    };

    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    message.success('配置保存成功');
  };

  // 添加 TTS 输入参数
  const handleAddTtsInputParam = () => {
    setTtsInputParams([...ttsInputParams, { name: '', type: 'input', value: '' }]);
  };

  // 删除 TTS 输入参数
  const handleRemoveTtsInputParam = (index: number) => {
    setTtsInputParams(ttsInputParams.filter((_, i) => i !== index));
  };

  // 更新 TTS 输入参数
  const handleUpdateTtsInputParam = (index: number, field: keyof TtsInputParam, value: string) => {
    const newParams = [...ttsInputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setTtsInputParams(newParams);
  };

  // 添加 TTS 输出参数
  const handleAddTtsOutputParam = () => {
    setTtsOutputParams([...ttsOutputParams, { name: '', value: '' }]);
  };

  // 删除 TTS 输出参数
  const handleRemoveTtsOutputParam = (index: number) => {
    setTtsOutputParams(ttsOutputParams.filter((_, i) => i !== index));
  };

  // 更新 TTS 输出参数
  const handleUpdateTtsOutputParam = (index: number, field: keyof TtsOutputParam, value: string) => {
    const newParams = [...ttsOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setTtsOutputParams(newParams);
  };

  // 添加 LLM 输出参数
  const handleAddLlmOutputParam = () => {
    setLlmOutputParams([...llmOutputParams, { name: '', type: 'string', description: '' }]);
  };

  // 删除 LLM 输出参数
  const handleRemoveLlmOutputParam = (index: number) => {
    setLlmOutputParams(llmOutputParams.filter((_, i) => i !== index));
  };

  // 更新 LLM 输出参数
  const handleUpdateLlmOutputParam = (index: number, field: keyof LlmOutputParam, value: string) => {
    const newParams = [...llmOutputParams];
    newParams[index] = { ...newParams[index], [field]: value };
    setLlmOutputParams(newParams);
  };

  // 自动保存输出节点配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'output') return;
    
    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    
    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证
      let hasValidData = false;
      
      // 检查是否有有效的参数配置
      if (outputParams.length > 0) {
        hasValidData = outputParams.some(param => param.name && (param.value || param.referenceNode));
      }
      
      // 或者有响应内容
      if (responseContent && responseContent.trim()) {
        hasValidData = true;
      }
      
      if (!hasValidData) return; // 没有有效数据，不保存
      
      // 保存到节点的 data 中
      const updatedData = {
        ...selectedNode.data,
        outputParams,
        responseContent
      };
      
      useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
      console.log('输出节点配置已自动保存');
    }, 500);
    
    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [outputParams, responseContent, selectedNode]);

  // 自动保存 LLM 节点配置
  useEffect(() => {
    if (!selectedNode) return;
    const nodeType = selectedNode.data?.type;
    if (!isLlmNodeType(String(nodeType || ''))) return;

    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }

    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证：至少有基本配置
      const hasBasicConfig = llmConfig.configId || llmConfig.apiUrl || llmConfig.apiKey || llmConfig.model || llmConfig.prompt;
      const hasParams = llmInputParams.length > 0 || llmOutputParams.length > 0;

      if (!hasBasicConfig && !hasParams) return; // 没有任何配置，不保存

      const useGlobalConfig = !!llmConfig.configId;
      const nextAgentStrategy = selectedNode.data?.type === 'react_agent' ? 'react' : llmConfig.agentStrategy;
      const selectedKnowledgeBaseName = llmConfig.knowledgeBaseId
        ? knowledgeBases.find((base) => String(base.id) === llmConfig.knowledgeBaseId)?.name || llmConfig.knowledgeBaseName
        : '';
      const updatedData = {
        ...selectedNode.data,
        provider: llmConfig.provider,
        configId: llmConfig.configId,
        apiUrl: useGlobalConfig ? '' : llmConfig.apiUrl,
        apiKey: useGlobalConfig ? '' : llmConfig.apiKey,
        model: useGlobalConfig ? '' : llmConfig.model,
        temperature: llmConfig.temperature,
        prompt: llmConfig.prompt,
        skillName: llmConfig.skillName,
        maxSteps: llmConfig.maxSteps,
        agentStrategy: nextAgentStrategy,
        tools: nextAgentStrategy === 'react' ? buildRuntimeToolSelection(llmConfig.tools, llmConfig.mcpToolIds) : [],
        memoryEnabled: llmConfig.memoryEnabled,
        memoryTopK: llmConfig.memoryTopK,
        mcpToolIds: nextAgentStrategy === 'react' ? llmConfig.mcpToolIds : [],
        knowledgeBaseId: llmConfig.knowledgeBaseId,
        knowledgeBaseName: selectedKnowledgeBaseName,
        knowledgeTopK: llmConfig.knowledgeTopK,
        knowledgeScoreThreshold: llmConfig.knowledgeScoreThreshold,
        inputParams: llmInputParams,
        outputParams: llmOutputParams
      };

      useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
      console.log('LLM节点配置已自动保存');
    }, 500);

    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [llmConfig, llmInputParams, llmOutputParams, knowledgeBases, selectedNode]);

  // 自动保存 TTS 节点配置
  useEffect(() => {
    if (!selectedNode || selectedNode.data?.type !== 'tts') return;
    
    // 清理之前的定时器
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    
    // 设置新的定时器（防抖500ms）
    autoSaveTimerRef.current = setTimeout(() => {
      // 基础验证：至少有基本配置
      const hasBasicConfig = ttsConfig.configId || ttsConfig.apiUrl || ttsConfig.apiKey || ttsConfig.model;
      const hasParams = ttsInputParams.length > 0 || ttsOutputParams.length > 0;
      
      if (!hasBasicConfig && !hasParams) return; // 没有任何配置，不保存
      
      const useGlobalConfig = !!ttsConfig.configId;
      const updatedData = {
        ...selectedNode.data,
        provider: ttsConfig.provider,
        configId: ttsConfig.configId,
        apiUrl: useGlobalConfig ? '' : ttsConfig.apiUrl,
        apiKey: useGlobalConfig ? '' : ttsConfig.apiKey,
        model: useGlobalConfig ? '' : ttsConfig.model,
        voice: ttsConfig.voice,
        languageType: ttsConfig.languageType,
        instruction: ttsConfig.instruction,
        speed: ttsConfig.speed,
        volume: ttsConfig.volume,
        sampleRate: ttsConfig.sampleRate,
        inputParams: ttsInputParams,
        outputParams: ttsOutputParams
      };
      
      useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
      console.log('TTS节点配置已自动保存');
    }, 500);
    
    // 清理函数
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [ttsConfig, ttsInputParams, ttsOutputParams, selectedNode]);

  const selectedNodeType = String(selectedNode?.data?.type || '');
  const isLegacyReActNode = selectedNodeType === 'react_agent';
  const isGenericLlmNode = selectedNodeType === 'llm';
  const isReActMode = (isGenericLlmNode && llmConfig.agentStrategy === 'react') || isLegacyReActNode;
  const isAgentPlanNode = [
    'web_search',
    'web_fetch',
    'memory_write',
    'memory_retrieve',
    'knowledge_upsert',
    'knowledge_retrieve',
    'image_generate',
    'video_generate'
  ].includes(selectedNodeType);
  const selectedNodeProvider = resolveSelectedNodeProvider(selectedNode);
  const agentToolOptions = [
    { label: '写入记忆', value: 'memory_write' }
  ];
  const availableLlmConfigs = isGenericLlmNode || isLegacyReActNode
    ? llmGlobalConfigs
    : llmGlobalConfigs.filter(
        (config) => normalizeProviderKey(config.provider) === selectedNodeProvider
      );
  const ttsProviderOptions = providerOptions.filter(option => ['qwen', 'step'].includes(option.value));
  const normalizedTtsProvider = normalizeProviderKey(ttsConfig.provider);
  const availableTtsConfigs = llmGlobalConfigs.filter(
    (config) => ['qwen', 'step'].includes(normalizeProviderKey(config.provider))
  );
  const isStepTtsProvider = normalizedTtsProvider === 'step';
  const hasTtsProvider = ['qwen', 'step'].includes(normalizedTtsProvider);
  const getDefaultTtsModel = (provider: string) => provider === 'step' ? 'stepaudio-2.5-tts' : provider === 'qwen' ? 'qwen3-tts-flash' : '';
  const getDefaultTtsVoice = (provider: string) => provider === 'step' ? 'cixingnansheng' : provider === 'qwen' ? 'Cherry' : '';
  const getTtsModelForProvider = (provider: string, ttsModel?: string | null, fallbackModel?: string | null) => {
    const normalizedProvider = normalizeProviderKey(provider);
    const explicitTtsModel = ttsModel?.trim() || '';
    if (explicitTtsModel) {
      return explicitTtsModel;
    }

    const trimmedModel = fallbackModel?.trim() || '';
    const lowerModel = trimmedModel.toLowerCase();

    if (normalizedProvider === 'step') {
      return lowerModel.includes('step') && lowerModel.includes('tts')
        ? trimmedModel
        : getDefaultTtsModel(normalizedProvider);
    }

    if (normalizedProvider === 'qwen') {
      return lowerModel.includes('tts')
        ? trimmedModel
        : getDefaultTtsModel(normalizedProvider);
    }

    return trimmedModel;
  };
  const getGlobalConfigLabel = (config: { provider: string; model?: string; ttsModel?: string; imageModel?: string; videoModel?: string }) => {
    const providerLabel = getProviderLabel(config.provider);
    const modelLabel = config.model ? `LLM: ${config.model}` : '';
    const ttsModelLabel = config.ttsModel ? `TTS: ${config.ttsModel}` : '';
    const imageModelLabel = config.imageModel ? `生图: ${config.imageModel}` : '';
    const videoModelLabel = config.videoModel ? `生视频: ${config.videoModel}` : '';
    return [providerLabel, modelLabel, ttsModelLabel, imageModelLabel, videoModelLabel].filter(Boolean).join(' / ');
  };
  const availableAgentPlanConfigs = llmGlobalConfigs.filter(
    (config) => normalizeProviderKey(config.provider) === 'volcengine_agent_plan'
  );
  const imageGenerationProviderOptions = providerOptions.filter(option => ['volcengine_agent_plan', 'step'].includes(option.value));
  const availableImageGenerateConfigs = llmGlobalConfigs.filter((config) => {
    const provider = normalizeProviderKey(config.provider);
    if (!['volcengine_agent_plan', 'step'].includes(provider)) {
      return false;
    }
    return provider === 'volcengine_agent_plan' || !!config.imageModel;
  });
  const availableToolConfigs = selectedNodeType === 'image_generate'
    ? availableImageGenerateConfigs
    : availableAgentPlanConfigs;
  const selectedToolProvider = normalizeProviderKey(String(selectedNode?.data?.provider || '')) || 'volcengine_agent_plan';
  const selectedToolConfig = selectedNode?.data?.configId
    ? llmGlobalConfigs.find(config => config.id === selectedNode.data?.configId)
    : undefined;
  const selectedImageModel = String(
    selectedNode?.data?.model ||
    selectedToolConfig?.imageModel ||
    selectedToolConfig?.model ||
    ''
  );
  const isStepImageEditModel = selectedToolProvider === 'step' && selectedImageModel === 'step-image-edit-2';
  const getDefaultStepImageSize = (model?: string) => model === 'step-image-edit-2' ? '768x1360' : '1280x800';
  const updateSelectedNodeData = (patch: Record<string, unknown>) => {
    if (!selectedNode) return;
    const updatedData = { ...selectedNode.data, ...patch };
    useWorkflowStore.getState().updateNode(selectedNode.id, updatedData);
    useWorkflowStore.getState().setSelectedNode({ ...selectedNode, data: updatedData });
  };
  const getAgentPlanInputParams = () => (
    Array.isArray(selectedNode?.data?.inputParams)
      ? selectedNode.data.inputParams as TtsInputParam[]
      : []
  );
  const getAgentPlanOutputParams = () => (
    Array.isArray(selectedNode?.data?.outputParams)
      ? selectedNode.data.outputParams as TtsOutputParam[]
      : []
  );
  const addAgentPlanInputParam = (name = '') => {
    updateSelectedNodeData({
      inputParams: [...getAgentPlanInputParams(), { name, type: 'reference', value: '' }]
    });
  };
  const updateAgentPlanInputParam = (index: number, field: keyof TtsInputParam, value: string) => {
    const nextParams = [...getAgentPlanInputParams()];
    nextParams[index] = { ...nextParams[index], [field]: value };
    updateSelectedNodeData({ inputParams: nextParams });
  };
  const removeAgentPlanInputParam = (index: number) => {
    updateSelectedNodeData({
      inputParams: getAgentPlanInputParams().filter((_, i) => i !== index)
    });
  };
  const addAgentPlanOutputParam = (name = '', value = '') => {
    updateSelectedNodeData({
      outputParams: [...getAgentPlanOutputParams(), { name, value }]
    });
  };
  const updateAgentPlanOutputParam = (index: number, field: keyof TtsOutputParam, value: string) => {
    const nextParams = [...getAgentPlanOutputParams()];
    nextParams[index] = { ...nextParams[index], [field]: value };
    updateSelectedNodeData({ outputParams: nextParams });
  };
  const removeAgentPlanOutputParam = (index: number) => {
    updateSelectedNodeData({
      outputParams: getAgentPlanOutputParams().filter((_, i) => i !== index)
    });
  };
  const getConditionRules = (): ConditionRule[] => (
    Array.isArray(selectedNode?.data?.conditions) ? selectedNode.data.conditions as ConditionRule[] : []
  );
  const addConditionRule = () => {
    const rules = getConditionRules();
    const newRule: ConditionRule = {
      id: `cond-${Date.now()}-${rules.length}`,
      field: 'input',
      operator: 'contains',
      value: ''
    };
    updateSelectedNodeData({ conditions: [...rules, newRule] });
  };
  const updateConditionRule = (index: number, patch: Partial<ConditionRule>) => {
    const rules = [...getConditionRules()];
    rules[index] = { ...rules[index], ...patch };
    updateSelectedNodeData({ conditions: rules });
  };
  const removeConditionRule = (index: number) => {
    updateSelectedNodeData({ conditions: getConditionRules().filter((_, i) => i !== index) });
  };
  const selectedNodeLabel = String(selectedNode?.data?.label || selectedNode?.id || '');

  return (
    <div className="workflow-editor-shell">
      {/* 顶部工具栏 */}
      <header className="workflow-topbar">
        <div className="workflow-title-group">
          <div className="workflow-brand-mark">P</div>
          <div>
            <div className="workflow-brand">PaiAgent</div>
            <Input
              value={workflowName}
              onChange={(e) => setWorkflowName(e.target.value)}
              className="workflow-name-input"
              placeholder="工作流名称"
              bordered={false}
            />
          </div>
          <Select
            value={engineType}
            onChange={(value) => setEngineType(value)}
            className="workflow-engine-select"
            options={[
              { value: 'dag', label: 'DAG 引擎' },
              { value: 'langgraph', label: 'LangGraph 引擎' }
            ]}
          />
        </div>
        
        <div className="workflow-actions">
          <LLMConfigModal />
          <Button
            icon={<DatabaseOutlined />}
            onClick={() => navigate('/knowledge')}
          >
            知识库
          </Button>
          <Button
            icon={<ApiOutlined />}
            onClick={() => navigate('/mcp-tools')}
          >
            MCP 工具
          </Button>
          <Button
            icon={<PlusOutlined />}
            onClick={handleCreateNew}
          >
            新建
          </Button>
          <Button
            icon={<FolderOpenOutlined />}
            onClick={handleOpenLoadModal}
          >
            加载
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            onClick={handleSave}
            loading={saving}
          >
            保存
          </Button>
          <Button
            icon={<BugOutlined />}
            onClick={handleOpenDebug}
            disabled={!activeWorkflowId}
          >
            调试
          </Button>
          <div className="workflow-user">
            <span className="workflow-user-name">{username}</span>
            <Button
              icon={<LogoutOutlined />}
              onClick={handleLogout}
              type="text"
              size="small"
            >
              登出
            </Button>
          </div>
        </div>
      </header>

      {/* 主要内容区域 */}
      <div className="workflow-workbench">
        {/* 左侧节点面板 */}
        <aside className="workflow-left-panel">
          <NodePanel onDragStart={handleDragStart} />
        </aside>

        {/* 中间画布 */}
        <main className="workflow-canvas-panel">
          <FlowCanvas onNodeClick={handleNodeClick} />
        </main>

        {/* 右侧配置面板 */}
        <aside className="workflow-config-panel">
          <div className="workflow-panel-heading">
            <div>
              <p className="workflow-panel-kicker">Inspector</p>
              <h3>节点配置</h3>
            </div>
            {selectedNode && <span className="workflow-node-pill">{selectedNodeType || 'node'}</span>}
          </div>
          {selectedNode ? (
            <div className="workflow-config-body">
              <div className="workflow-selected-card">
                <div className="workflow-selected-icon">{selectedNodeLabel.slice(0, 1).toUpperCase()}</div>
                <div className="min-w-0">
                  <p className="workflow-selected-title">{selectedNodeLabel}</p>
                  <p className="workflow-selected-meta">{selectedNode.id}</p>
                </div>
              </div>
                
                {/* 输入节点配置 */}
                {selectedNode.data?.type === 'input' && (
                  <Form layout="vertical" className="workflow-config-form">
                    <Form.Item label="变量名">
                      <Input value="user_input" disabled />
                    </Form.Item>
                    <Form.Item label="变量类型">
                      <Input value="String" disabled />
                    </Form.Item>
                    <Form.Item label="描述">
                      <Input.TextArea value="用户本轮的输入内容" disabled rows={2} />
                    </Form.Item>
                    <Form.Item label="是否必要">
                      <Checkbox checked disabled>必要</Checkbox>
                    </Form.Item>
                  </Form>
                )}

                {/* 输出节点配置 */}
                {selectedNode.data?.type === 'output' && (
                  <Form layout="vertical" className="workflow-config-form">
                    {/* 输出配置 */}
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">输出配置</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddOutputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {outputParams.map((param, index) => (
                        <div key={index} className="workflow-param-row">
                          <div>
                            <Input 
                              placeholder="参数名"
                              value={param.name}
                              onChange={(e) => handleUpdateOutputParam(index, 'name', e.target.value)}
                              style={{ width: '100px' }}
                            />
                          </div>
                          <div>
                            <Select
                              value={param.type}
                              onChange={(value) => handleUpdateOutputParam(index, 'type', value)}
                              style={{ width: '80px' }}
                            >
                              <Select.Option value="input">输入</Select.Option>
                              <Select.Option value="reference">引用</Select.Option>
                            </Select>
                          </div>
                          <div className="flex-1">
                            {param.type === 'input' ? (
                              <Input 
                                placeholder="输入值"
                                value={param.value}
                                onChange={(e) => handleUpdateOutputParam(index, 'value', e.target.value)}
                              />
                            ) : (
                              <Select
                                placeholder="选择参数"
                                value={param.referenceNode}
                                onChange={(value) => handleUpdateOutputParam(index, 'referenceNode', value)}
                                className="w-full workflow-reference-select"
                                popupMatchSelectWidth={false}
                                dropdownStyle={{ minWidth: 360 }}
                              >
                                {getReferenceableParams().map(param => (
                                  <Select.Option key={param.value} value={param.value} title={param.label}>
                                    {param.label}
                                  </Select.Option>
                                ))}
                              </Select>
                            )}
                          </div>
                          <Button 
                            type="text" 
                            danger 
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveOutputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {outputParams.length === 0 && (
                        <div className="workflow-config-empty">
                          点击"添加"按钮添加输出参数
                        </div>
                      )}
                    </div>

                    {/* 回答内容配置 */}
                    <Form.Item label="回答内容配置">
                      <Input.TextArea 
                        rows={6}
                        placeholder="使用 {{参数名}} 引用输出配置中的参数"
                        value={responseContent}
                        onChange={(e) => setResponseContent(e.target.value)}
                      />
                      <div className="workflow-field-hint">
                        💡 提示: 使用 {'{{'} 参数名 {'}'} 引用上面定义的参数
                      </div>
                    </Form.Item>

                    <Button type="primary" block onClick={handleSaveOutputConfig}>
                      保存配置
                    </Button>
                  </Form>
                )}

                {/* LLM 节点配置 */}
                {isLlmNodeType(selectedNodeType) && (
                  <Form layout="vertical" className="workflow-config-form">
                    <Alert
                      className="workflow-form-example"
                      type="info"
                      showIcon
                      message="没配置过？看这里"
                      description="最简单的跑法：不选「全局配置」，直接在下面手填供应商=DeepSeek、API 地址=https://api.deepseek.com、模型名称=deepseek-chat，密钥去 platform.deepseek.com 申请。提示词模板里用 {{参数名}} 引用输入，比如「请回答用户的问题：{{user_input}}」。如果同一个 Key 要在多个节点里重复用，去顶部「模型管理」建一条全局配置，这里选它就不用每次都填。"
                    />
                    {/* 输入参数配置 */}
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">输入参数</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddLlmInputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {llmInputParams.map((param, index) => (
                        <div key={index} className="workflow-param-row">
                          <Input 
                            placeholder="参数名"
                            value={param.name}
                            onChange={(e) => handleUpdateLlmInputParam(index, 'name', e.target.value)}
                            style={{ width: '90px' }}
                          />
                          <Select
                            value={param.type}
                            onChange={(value) => handleUpdateLlmInputParam(index, 'type', value)}
                            style={{ width: '70px' }}
                          >
                            <Select.Option value="input">输入</Select.Option>
                            <Select.Option value="reference">引用</Select.Option>
                          </Select>
                          <div className="flex-1">
                            {param.type === 'input' ? (
                              <Input 
                                placeholder="输入值"
                                value={param.value}
                                onChange={(e) => handleUpdateLlmInputParam(index, 'value', e.target.value)}
                              />
                            ) : (
                              <Select
                                placeholder="选择参数"
                                value={param.referenceNode}
                                onChange={(value) => handleUpdateLlmInputParam(index, 'referenceNode', value)}
                                className="w-full workflow-reference-select"
                                popupMatchSelectWidth={false}
                                dropdownStyle={{ minWidth: 360 }}
                              >
                                {getReferenceableParams().map(p => (
                                  <Select.Option key={p.value} value={p.value} title={p.label}>
                                    {p.label}
                                  </Select.Option>
                                ))}
                              </Select>
                            )}
                          </div>
                          <Button 
                            type="text" 
                            danger 
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveLlmInputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {llmInputParams.length === 0 && (
                        <div className="workflow-config-empty">
                          点击"添加"按钮添加输入参数
                        </div>
                      )}
                    </div>

                    {/* 输出参数配置 */}
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">输出参数</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddLlmOutputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {llmOutputParams.map((param, index) => (
                        <div key={index} className="workflow-param-row">
                          <Input 
                            placeholder="变量名"
                            value={param.name}
                            onChange={(e) => handleUpdateLlmOutputParam(index, 'name', e.target.value)}
                            style={{ width: '100px' }}
                          />
                          <Input
                            value="string"
                            disabled
                            style={{ width: '70px' }}
                          />
                          <div className="flex-1">
                            <Input 
                              placeholder="描述（可选）"
                              value={param.description}
                              onChange={(e) => handleUpdateLlmOutputParam(index, 'description', e.target.value)}
                            />
                          </div>
                          <Button 
                            type="text" 
                            danger 
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveLlmOutputParam(index)}
                          />
                        </div>
                      ))}
                      
                      {llmOutputParams.length === 0 && (
                        <div className="workflow-config-empty">
                          点击"添加"按钮添加输出参数
                        </div>
                      )}
                    </div>

                    <Form.Item label="提示词模板" required>
                      <Input.TextArea
                        rows={12}
                        placeholder="输入提示词模板，使用 {{参数名}} 引用输入参数"
                        value={llmConfig.prompt}
                        onChange={(e) => setLlmConfig({...llmConfig, prompt: e.target.value})}
                        style={{ fontFamily: 'monospace', fontSize: '12px' }}
                      />
                      <div className="workflow-field-hint">
                        💡 使用 {'{{'} 参数名 {'}'} 引用上面定义的输入参数
                      </div>
                    </Form.Item>

	                    {isGenericLlmNode && (
	                      <Form.Item label="Agent策略">
	                        <Select
	                          value={llmConfig.agentStrategy}
	                          onChange={(value) => setLlmConfig({
	                            ...llmConfig,
	                            agentStrategy: value,
	                            tools: value === 'react' ? llmConfig.tools : [],
	                            mcpToolIds: value === 'react' ? llmConfig.mcpToolIds : []
	                          })}
	                        >
	                          <Select.Option value="none">普通大模型调用</Select.Option>
	                          <Select.Option value="react">ReAct（支持 Tools）</Select.Option>
	                        </Select>
	                      </Form.Item>
	                    )}

	                    {isReActMode && (
	                      <Form.Item label="最大 ReAct 步数">
	                        <Input
                          type="number"
                          min="1"
                          max="20"
                          value={llmConfig.maxSteps}
                          onChange={(e) => setLlmConfig({
                            ...llmConfig,
                            maxSteps: Math.min(20, Math.max(1, parseInt(e.target.value, 10) || 5))
                          })}
                        />
                        <div className="workflow-field-hint">
	                          每一步只能做一次工具调用或给出最终答案，超过步数会停止执行
	                        </div>
	                      </Form.Item>
	                    )}

	                    {isReActMode && (
	                      <Form.Item label="工具列表">
	                        <Checkbox.Group
	                          value={llmConfig.tools}
	                          options={agentToolOptions}
	                          onChange={(values) => setLlmConfig({
	                            ...llmConfig,
	                            tools: values.map(String)
	                          })}
	                        />
	                        <div className="workflow-field-hint">
	                          写入记忆用于让 ReAct 在需要时保存稳定结论。
	                        </div>
	                      </Form.Item>
	                    )}

	                    {isReActMode && (
	                      <Form.Item label="MCP 工具">
	                        <Select
	                          mode="multiple"
	                          value={llmConfig.mcpToolIds}
	                          placeholder="选择当前大模型节点可调用的 MCP 工具"
	                          allowClear
	                          onChange={(values) => {
	                            const nextIds = values.map(String);
	                            setLlmConfig({
	                              ...llmConfig,
	                              mcpToolIds: nextIds
	                            });
	                          }}
	                          options={mcpTools
	                            .filter((tool) => tool.enabled === 1)
	                            .map((tool) => ({
	                              value: String(tool.id),
	                              label: `${tool.name}（${tool.toolName}）`
	                            }))}
	                        />
	                        <div className="workflow-field-hint">
	                          MCP 工具作为大模型节点的可选工具组件使用；可在顶部“MCP 工具”中添加 Agent Plan 联网搜索。
	                        </div>
	                      </Form.Item>
	                    )}

	                    <div className="workflow-config-section">
	                      <div className="workflow-config-section-title">上下文能力</div>
	                      <Form.Item>
	                        <Checkbox
	                          checked={llmConfig.memoryEnabled}
	                          onChange={(e) => setLlmConfig({
	                            ...llmConfig,
	                            memoryEnabled: e.target.checked
	                          })}
	                        >
	                          启用记忆召回
	                        </Checkbox>
	                        <div className="workflow-field-hint">
	                          记忆是大模型节点的上下文能力：执行此节点前召回相关记忆，注入到当前提示词。
	                        </div>
	                      </Form.Item>
	                      {llmConfig.memoryEnabled && (
	                        <Form.Item label="记忆 TopK">
	                          <Input
	                            type="number"
	                            min="1"
	                            max="20"
	                            value={llmConfig.memoryTopK}
	                            onChange={(e) => setLlmConfig({
	                              ...llmConfig,
	                              memoryTopK: Math.min(20, Math.max(1, parseInt(e.target.value, 10) || 5))
	                            })}
	                          />
	                        </Form.Item>
	                      )}
	                      <Form.Item label="知识库">
	                        <Select
	                          value={llmConfig.knowledgeBaseId}
	                          placeholder="选择知识库作为当前大模型节点的 RAG 上下文"
	                          allowClear
	                          onChange={(value) => {
	                            const selectedBase = knowledgeBases.find((base) => String(base.id) === value);
	                            setLlmConfig({
	                              ...llmConfig,
	                              knowledgeBaseId: value,
	                              knowledgeBaseName: selectedBase?.name || ''
	                            });
	                          }}
	                          options={knowledgeBases.map((base) => ({
	                            value: String(base.id),
	                            label: `${base.name}（${base.documentCount || 0} 文档 / ${base.chunkCount || 0} 分片）`
	                          }))}
	                        />
	                        <div className="workflow-field-hint">
	                          知识库作为大模型节点的可选组件使用；可在顶部“知识库”中创建、导入文本并建立索引。
	                        </div>
	                      </Form.Item>
	                      {llmConfig.knowledgeBaseId && (
	                        <>
	                          <Form.Item label="知识库 TopK">
	                            <Input
	                              type="number"
	                              min="1"
	                              max="20"
	                              value={llmConfig.knowledgeTopK}
	                              onChange={(e) => setLlmConfig({
	                                ...llmConfig,
	                                knowledgeTopK: Math.min(20, Math.max(1, parseInt(e.target.value, 10) || 5))
	                              })}
	                            />
	                          </Form.Item>
	                          <Form.Item label="最低相关度">
	                            <Input
	                              type="number"
	                              min="0"
	                              max="1"
	                              step="0.05"
	                              value={llmConfig.knowledgeScoreThreshold}
	                              onChange={(e) => setLlmConfig({
	                                ...llmConfig,
	                                knowledgeScoreThreshold: Math.min(1, Math.max(0, parseFloat(e.target.value) || 0))
	                              })}
	                            />
	                          </Form.Item>
	                        </>
	                      )}
	                    </div>

                    {/* 全局配置选择 */}
                    <Form.Item
                      label="全局配置"
                      required={!llmConfig.configId && !llmConfig.apiUrl}
                    >
                      <Select
                        value={llmConfig.configId}
                        onChange={(value) => {
                          if (value) {
                            const config = llmGlobalConfigs.find(c => c.id === value);
                            if (config) {
                              setLlmConfig({
                                ...llmConfig,
                                provider: normalizeProviderKey(config.provider),
                                configId: value,
                                apiUrl: config.apiUrl,
                                apiKey: '',
                                model: config.model
                              });
                            }
                          } else {
                            setLlmConfig({
                              ...llmConfig,
                              provider: isGenericLlmNode ? '' : selectedNodeProvider,
                              configId: undefined,
                              apiUrl: '',
                              apiKey: '',
                              model: '',
                              temperature: llmConfig.temperature
                            });
                          }
                        }}
                        placeholder="选择一个全局配置"
                        allowClear
                      >
                        {availableLlmConfigs.map(config => (
                            <Select.Option key={config.id} value={config.id}>
                              {isGenericLlmNode ? getGlobalConfigLabel(config) : config.model}
                            </Select.Option>
                          ))}
                      </Select>
                      <div className="workflow-field-hint">
                        💡 选择全局配置后无需填写下方 API 信息
                      </div>
                    </Form.Item>

                    {/* API 配置（未选择全局配置时显示） */}
                    {!llmConfig.configId && (
                      <>
                        {isGenericLlmNode && (
                          <Form.Item label="供应商" required>
                            <Select
                              value={llmConfig.provider || undefined}
                              placeholder="选择这条节点配置要使用的供应商"
                              options={providerOptions}
                              onChange={(value) => setLlmConfig({ ...llmConfig, provider: value })}
                            />
                          </Form.Item>
                        )}
                        <div className="workflow-config-warning">
                          ⚠️ 未选择全局配置，请手动填写以下 API 信息
                        </div>
                        <Form.Item label="API 地址" required>
                          <Input
                            placeholder="例如: https://dashscope.aliyuncs.com/compatible-mode"
                            value={llmConfig.apiUrl}
                            onChange={(e) => setLlmConfig({...llmConfig, apiUrl: e.target.value})}
                          />
                          <div className="workflow-field-hint">
                            填写兼容接口根地址，不要追加 <code>/v1</code> 或 <code>/v1/chat/completions</code>
                          </div>
                        </Form.Item>
                        <Form.Item label="API 密钥" required>
                          <Input.Password
                            placeholder="输入 API Key"
                            value={llmConfig.apiKey}
                            onChange={(e) => setLlmConfig({...llmConfig, apiKey: e.target.value})}
                          />
                        </Form.Item>
                        <Form.Item label="模型名称" required>
                          <Input
                            placeholder="例如: deepseek-chat, claude-3-5-sonnet-20241022"
                            value={llmConfig.model}
                            onChange={(e) => setLlmConfig({...llmConfig, model: e.target.value})}
                          />
                        </Form.Item>
                      </>
                    )}

                    <Form.Item label="温度">
                      <Input
                        type="number"
                        step="0.1"
                        min="0"
                        max="2"
                        value={llmConfig.temperature}
                        onChange={(e) => setLlmConfig({...llmConfig, temperature: parseFloat(e.target.value) || 0.7})}
                      />
                      <div className="workflow-field-hint">
                        控制当前节点输出随机性，范围 0-2，值越高越随机
                      </div>
                    </Form.Item>

                    {/* 已选择全局配置时显示配置信息 */}
                    {llmConfig.configId && (
                      <div className="workflow-config-summary">
                        <div className="font-medium mb-2">当前使用全局配置：</div>
                        <div>供应商: {getProviderLabel(llmConfig.provider)}</div>
                        <div>API 地址: {llmConfig.apiUrl}</div>
                        <div>模型: {llmConfig.model}</div>
                      </div>
                    )}

                    <Form.Item label="技能 (Skill)">
                      <SkillSelector
                        value={llmConfig.skillName}
                        onChange={(value) => setLlmConfig({...llmConfig, skillName: value || ''})}
                      />
                      <div className="workflow-field-hint">
                        选择一个技能来增强 LLM 的能力，LLM 会自动获取技能指南
                      </div>
                    </Form.Item>
                    <Button type="primary" block onClick={handleSaveLlmConfig}>
                      保存配置
                    </Button>
                  </Form>
                )}

                {/* TTS 节点配置 (超拟人音频) */}
                {selectedNode.data?.type === 'tts' && (
                  <Form layout="vertical" className="workflow-config-form">
                    <Alert
                      className="workflow-form-example"
                      type="info"
                      showIcon
                      message="示例：用通义千问把文字念出来"
                      description="供应商选「通义千问」，API 地址填 https://dashscope.aliyuncs.com/api/v1，API Key 在阿里云百炼控制台领取，模型名称填 qwen3-tts-flash，音色随便选一个比如 Cherry。上面「输入配置」里加一个 text 参数、引用上一个节点的回答文本，就能把大模型的答案转成语音。"
                    />
                    {/* 输入配置 */}
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">输入配置</label>
                        <Button 
                          type="dashed" 
                          size="small" 
                          icon={<PlusOutlined />}
                          onClick={handleAddTtsInputParam}
                        >
                          添加
                        </Button>
                      </div>
                      
                      {ttsInputParams.map((param, index) => (
                        <div key={index} className="mb-4 p-3 bg-gray-50 rounded">
                          <div className="workflow-param-row is-compact">
                            <Input 
                              placeholder="参数名 (如: text)"
                              value={param.name}
                              onChange={(e) => handleUpdateTtsInputParam(index, 'name', e.target.value)}
                              style={{ width: 120 }}
                            />
                            <Select
                              value={param.type}
                              onChange={(value) => handleUpdateTtsInputParam(index, 'type', value)}
                              style={{ width: 100 }}
                            >
                              <Select.Option value="input">输入</Select.Option>
                              <Select.Option value="reference">引用</Select.Option>
                            </Select>
                            <Button 
                              type="text" 
                              danger 
                              icon={<DeleteOutlined />}
                              onClick={() => handleRemoveTtsInputParam(index)}
                            />
                          </div>
                          <div>
                            {param.type === 'input' ? (
                              <Input.TextArea
                                placeholder="输入值"
                                value={param.value}
                                onChange={(e) => handleUpdateTtsInputParam(index, 'value', e.target.value)}
                                rows={2}
                              />
                            ) : (
                              <Select
                                placeholder="选择引用参数"
                                value={param.referenceNode}
                                onChange={(value) => handleUpdateTtsInputParam(index, 'referenceNode', value)}
                                className="workflow-reference-select"
                                popupMatchSelectWidth={false}
                                dropdownStyle={{ minWidth: 360 }}
                                style={{ width: '100%' }}
                              >
                                {getReferenceableParams().map((p) => (
                                  <Select.Option key={p.value} value={p.value} title={p.label}>
                                    {p.label}
                                  </Select.Option>
                                ))}
                              </Select>
                            )}
                          </div>
                        </div>
                      ))}
                      
                      {ttsInputParams.length === 0 && (
                        <div className="workflow-config-empty">
                          暂无输入参数,点击"添加"按钮创建 text 参数
                        </div>
                      )}
                    </div>

                    {/* 基本信息 */}
                    <div className="workflow-config-section">
                      <label className="workflow-config-section-title">基本信息</label>
                      <Form.Item label="全局配置" required={!ttsConfig.configId && !ttsConfig.apiUrl}>
                        <Select
                          value={ttsConfig.configId}
                          placeholder="选择一个通义千问或阶跃星辰配置"
                          allowClear
                          onChange={(value) => {
                            if (value) {
                              const config = llmGlobalConfigs.find(c => c.id === value);
                              if (config) {
                                const provider = normalizeProviderKey(config.provider);
                                const providerChanged = provider !== normalizedTtsProvider;
                                setTtsConfig({
                                  ...ttsConfig,
                                  provider,
                                  configId: value,
                                  apiUrl: config.apiUrl,
                                  apiKey: '',
                                  model: getTtsModelForProvider(provider, config.ttsModel, config.model),
                                  voice: providerChanged ? getDefaultTtsVoice(provider) : ttsConfig.voice
                                });
                              }
                            } else {
                              setTtsConfig({
                                ...ttsConfig,
                                configId: undefined,
                                apiUrl: '',
                                apiKey: '',
                                model: getDefaultTtsModel(normalizedTtsProvider)
                              });
                            }
                          }}
                        >
                          {availableTtsConfigs.map(config => (
                            <Select.Option key={config.id} value={config.id}>
                              {getGlobalConfigLabel(config)}
                            </Select.Option>
                          ))}
                        </Select>
                        <div className="workflow-field-hint">
                          选择全局配置后无需在节点中保存 API Key。
                        </div>
                      </Form.Item>

                      {!ttsConfig.configId && (
                        <>
                          <Form.Item label="供应商" required>
                            <Select
                              value={ttsConfig.provider}
                              options={ttsProviderOptions}
                              onChange={(value) => setTtsConfig({
                                ...ttsConfig,
                                provider: value,
                                model: getDefaultTtsModel(value),
                                voice: getDefaultTtsVoice(value)
                              })}
                            />
                          </Form.Item>
                          <div className="workflow-config-warning">
                            ⚠️ 未选择全局配置，请手动填写以下 API 信息
                          </div>
                          <Form.Item label="API 地址" required>
                            <Input
                              placeholder={isStepTtsProvider ? 'https://api.stepfun.com/v1' : 'https://dashscope.aliyuncs.com/api/v1'}
                              value={ttsConfig.apiUrl}
                              onChange={(e) => setTtsConfig({ ...ttsConfig, apiUrl: e.target.value })}
                            />
                          </Form.Item>
                          <Form.Item label="API Key" required>
                            <Input.Password
                              placeholder={isStepTtsProvider ? '请输入 StepFun API Key' : '请输入阿里百炼 API Key'}
                              value={ttsConfig.apiKey}
                              onChange={(e) => setTtsConfig({ ...ttsConfig, apiKey: e.target.value })}
                            />
                          </Form.Item>
                          <Form.Item label="模型名称" required>
                            <Input
                              placeholder={isStepTtsProvider ? 'stepaudio-2.5-tts' : 'qwen3-tts-flash'}
                              value={ttsConfig.model}
                              onChange={(e) => setTtsConfig({ ...ttsConfig, model: e.target.value })}
                            />
                          </Form.Item>
                        </>
                      )}

                      {ttsConfig.configId && (
                        <div className="workflow-config-summary">
                          <div className="font-medium mb-2">当前使用全局配置：</div>
                          <div>供应商: {getProviderLabel(ttsConfig.provider)}</div>
                          <div>API 地址: {ttsConfig.apiUrl}</div>
                          <div>模型: {ttsConfig.model}</div>
                        </div>
                      )}
                    </div>

                    {/* 语音参数 */}
                    <div className="workflow-config-section">
                      <label className="workflow-config-section-title">语音参数</label>
                      {!hasTtsProvider ? (
                        <div className="workflow-config-empty">
                          请先选择全局配置或手动选择供应商。
                        </div>
                      ) : (
                        <div className="space-y-3">
                          <Form.Item label="音色 (voice)" className="mb-0">
                            {isStepTtsProvider ? (
                              <Input
                                placeholder="例如: cixingnansheng"
                                value={ttsConfig.voice}
                                onChange={(e) => setTtsConfig({ ...ttsConfig, voice: e.target.value })}
                              />
                            ) : (
                              <Select
                                showSearch
                                value={ttsConfig.voice || undefined}
                                onChange={(value) => setTtsConfig({ ...ttsConfig, voice: value })}
                              >
                                <Select.Option value="Cherry">Cherry (芊悦)</Select.Option>
                                <Select.Option value="Serena">Serena (苏瑶)</Select.Option>
                                <Select.Option value="Ethan">Ethan (晨煦)</Select.Option>
                                <Select.Option value="Chelsie">Chelsie (千雪)</Select.Option>
                                <Select.Option value="Momo">Momo (茉兔)</Select.Option>
                                <Select.Option value="Vivian">Vivian (十三)</Select.Option>
                                <Select.Option value="Moon">Moon (月白)</Select.Option>
                                <Select.Option value="Maia">Maia (四月)</Select.Option>
                                <Select.Option value="Kai">Kai (凯)</Select.Option>
                                <Select.Option value="Nofish">Nofish (不吃鱼)</Select.Option>
                                <Select.Option value="Bella">Bella (萌宝)</Select.Option>
                                <Select.Option value="Jennifer">Jennifer (詹妮弗)</Select.Option>
                                <Select.Option value="Ryan">Ryan (甜茶)</Select.Option>
                                <Select.Option value="Katerina">Katerina (卡捷琳娜)</Select.Option>
                                <Select.Option value="Aiden">Aiden (艾登)</Select.Option>
                              </Select>
                            )}
                          </Form.Item>

                          {!isStepTtsProvider && (
                            <Form.Item label="语言类型 (language_type)" className="mb-0">
                              <Select
                                value={ttsConfig.languageType}
                                onChange={(value) => setTtsConfig({ ...ttsConfig, languageType: value })}
                              >
                                <Select.Option value="Auto">Auto</Select.Option>
                              </Select>
                            </Form.Item>
                          )}

                          {isStepTtsProvider && (
                            <>
                              <Form.Item
                                label="全局语境 (instruction)"
                                className="mb-0"
                                help={`${ttsConfig.instruction.length}/200`}
                              >
                                <Input.TextArea
                                  rows={3}
                                  maxLength={200}
                                  placeholder="例如: 语气紧张，语速偏快，带明显压迫感"
                                  value={ttsConfig.instruction}
                                  onChange={(e) => setTtsConfig({ ...ttsConfig, instruction: e.target.value })}
                                />
                              </Form.Item>

                              <Form.Item label="语速 (speed)" className="mb-0">
                                <Input
                                  type="number"
                                  step="0.1"
                                  min="0.5"
                                  max="2"
                                  value={ttsConfig.speed}
                                  onChange={(e) => setTtsConfig({ ...ttsConfig, speed: parseFloat(e.target.value) || 1 })}
                                />
                              </Form.Item>

                              <Form.Item label="音量 (volume)" className="mb-0">
                                <Input
                                  type="number"
                                  step="0.1"
                                  min="0.1"
                                  max="2"
                                  value={ttsConfig.volume}
                                  onChange={(e) => setTtsConfig({ ...ttsConfig, volume: parseFloat(e.target.value) || 1 })}
                                />
                              </Form.Item>

                              <Form.Item label="采样率 (sample_rate)" className="mb-0">
                                <Select
                                  value={ttsConfig.sampleRate}
                                  onChange={(value) => setTtsConfig({ ...ttsConfig, sampleRate: value })}
                                >
                                  <Select.Option value={8000}>8000</Select.Option>
                                  <Select.Option value={16000}>16000</Select.Option>
                                  <Select.Option value={22050}>22050</Select.Option>
                                  <Select.Option value={24000}>24000</Select.Option>
                                  <Select.Option value={48000}>48000</Select.Option>
                                </Select>
                              </Form.Item>
                            </>
                          )}
                        </div>
                      )}
                    </div>

                    {/* 输出配置 */}
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">输出配置</label>
                        <Button
                          type="dashed"
                          size="small"
                          icon={<PlusOutlined />}
                          onClick={handleAddTtsOutputParam}
                        >
                          添加
                        </Button>
                      </div>

                      {ttsOutputParams.map((param, index) => (
                        <div key={index} className="workflow-param-row">
                          <Input
                            placeholder="参数名 (如: voice_url)"
                            value={param.name}
                            onChange={(e) => handleUpdateTtsOutputParam(index, 'name', e.target.value)}
                            style={{ flex: 1 }}
                          />
                          <Input
                            placeholder="参数值 (引用字段,如: audioUrl)"
                            value={param.value}
                            onChange={(e) => handleUpdateTtsOutputParam(index, 'value', e.target.value)}
                            style={{ flex: 1 }}
                          />
                          <Button
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => handleRemoveTtsOutputParam(index)}
                          />
                        </div>
                      ))}

                      {ttsOutputParams.length === 0 && (
                        <div className="workflow-config-empty">
                          暂无输出参数,点击"添加"按钮创建 voice_url 参数
                        </div>
                      )}
                    </div>

	                    <Button type="primary" block onClick={handleSaveTtsConfig}>
	                      保存配置
	                    </Button>
	                  </Form>
	                )}

	                {/* Agent Plan / Harness 节点配置 */}
	                {isAgentPlanNode && (
	                  <Form layout="vertical" className="workflow-config-form">
                    {selectedNodeType === 'image_generate' && (
                      <Alert
                        className="workflow-form-example"
                        type="info"
                        showIcon
                        message="示例：接一个真的能出图的配置"
                        description="供应商选「阶跃星辰」比较好申请，API 地址填 https://api.stepfun.com/v1，API Key 去阶跃星辰开放平台领取，模型覆盖填 step-1x-medium。下面「图片提示词」里已经放了一个可以直接用的示例，改改主体描述就能跑。"
                      />
                    )}
                    {selectedNodeType === 'video_generate' && (
                      <Alert
                        className="workflow-form-example"
                        type="info"
                        showIcon
                        message="示例：接一个真的能出视频的配置"
                        description="目前视频生成走的是火山方舟（豆包 Seedance），全局配置里选一个已经建好的火山方舟配置最省事；没有的话去顶部「模型管理」新建一个，供应商选火山方舟 Agent Plan，API Key 在火山引擎控制台领取。下面「视频提示词」里已经放了一个可以直接用的示例。"
                      />
                    )}
                    <Form.Item label={selectedNodeType === 'image_generate' ? '图片生成全局配置' : 'Agent Plan 全局配置'}>
                      <Select
                        value={(selectedNode.data?.configId as number) || undefined}
                        placeholder={selectedNodeType === 'image_generate' ? '选择火山方舟或阶跃星辰图片配置' : '选择火山方舟 Agent Plan 配置'}
                        allowClear
                        onChange={(value) => {
	                          if (value) {
	                            const config = llmGlobalConfigs.find(c => c.id === value);
	                            const provider = config ? normalizeProviderKey(config.provider) : 'volcengine_agent_plan';
                              const imageModel = config?.imageModel || config?.model || '';
	                            updateSelectedNodeData({
	                              provider,
	                              configId: value,
	                              apiUrl: config?.apiUrl || '',
	                              apiKey: '',
	                              model: '',
	                              size: provider === 'step' ? getDefaultStepImageSize(imageModel) : ((selectedNode.data?.size as string) || '2K'),
                                steps: provider === 'step' && imageModel === 'step-image-edit-2' ? 8 : selectedNode.data?.steps,
                                cfgScale: provider === 'step' && imageModel === 'step-image-edit-2' ? 1 : selectedNode.data?.cfgScale,
                                textMode: provider === 'step' && imageModel === 'step-image-edit-2' ? true : selectedNode.data?.textMode
	                            });
                          } else {
                            updateSelectedNodeData({
                              provider: selectedNodeType === 'image_generate' ? selectedToolProvider : 'volcengine_agent_plan',
                              configId: undefined,
                              apiUrl: '',
                              apiKey: '',
                              model: ''
	                            });
	                          }
                        }}
                      >
                        {availableToolConfigs.map(config => (
                          <Select.Option key={config.id} value={config.id}>
                            {getGlobalConfigLabel(config)}
                          </Select.Option>
                        ))}
	                      </Select>
	                    </Form.Item>

                    {!selectedNode.data?.configId && (
                      <>
                        {selectedNodeType === 'image_generate' && (
                          <Form.Item label="供应商">
                            <Select
                              value={selectedToolProvider}
                              options={imageGenerationProviderOptions}
	                              onChange={(value) => updateSelectedNodeData({
	                                provider: value,
	                                apiUrl: value === 'step' ? 'https://api.stepfun.com/v1' : '',
	                                model: '',
	                                size: value === 'step' ? '1280x800' : '2K'
	                              })}
                            />
                          </Form.Item>
                        )}
                        <Form.Item label="API 地址">
                          <Input
                            placeholder={selectedNodeType === 'image_generate' && selectedToolProvider === 'step'
                              ? (isStepImageEditModel ? 'https://api.stepfun.com/step_plan/v1' : 'https://api.stepfun.com/v1')
                              : 'https://ark.cn-beijing.volces.com/api/plan/v3'}
                            value={(selectedNode.data?.apiUrl as string) || ''}
                            onChange={(e) => updateSelectedNodeData({
                              provider: selectedNodeType === 'image_generate' ? selectedToolProvider : 'volcengine_agent_plan',
                              apiUrl: e.target.value
                            })}
                          />
                        </Form.Item>
	                        <Form.Item label="API Key">
	                          <Input.Password
	                            value={(selectedNode.data?.apiKey as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ apiKey: e.target.value })}
	                          />
	                        </Form.Item>
                        <Form.Item label="模型覆盖">
                          <Input
                            placeholder={selectedNodeType === 'image_generate' && selectedToolProvider === 'step'
                              ? '可选，例如 step-image-edit-2 或 step-1x-medium'
                              : '可选。为空时使用全局配置里的对应模型'}
                            value={(selectedNode.data?.model as string) || ''}
                            onChange={(e) => {
                              const model = e.target.value.trim();
                              updateSelectedNodeData({
                                model,
                                size: selectedToolProvider === 'step' && model ? getDefaultStepImageSize(model) : selectedNode.data?.size,
                                steps: selectedToolProvider === 'step' && model === 'step-image-edit-2' ? 8 : selectedNode.data?.steps,
                                cfgScale: selectedToolProvider === 'step' && model === 'step-image-edit-2' ? 1 : selectedNode.data?.cfgScale,
                                textMode: selectedToolProvider === 'step' && model === 'step-image-edit-2' ? true : selectedNode.data?.textMode
                              });
                            }}
                          />
	                        </Form.Item>
	                      </>
	                    )}

	                    {selectedNodeType === 'web_search' && (
	                      <>
	                        <Form.Item label="搜索问题">
	                          <Input.TextArea
	                            rows={3}
	                            value={(selectedNode.data?.query as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ query: e.target.value })}
	                          />
	                        </Form.Item>
	                        <Form.Item label="结果数量">
	                          <Input
	                            type="number"
	                            min="1"
	                            max="10"
	                            value={(selectedNode.data?.limit as number) || 5}
	                            onChange={(e) => updateSelectedNodeData({ limit: parseInt(e.target.value, 10) || 5 })}
	                          />
	                        </Form.Item>
	                      </>
	                    )}

	                    {selectedNodeType === 'web_fetch' && (
	                      <Form.Item label="URL 列表">
	                        <Input.TextArea
	                          rows={4}
	                          placeholder="多个 URL 可用换行分隔"
	                          value={(selectedNode.data?.urls as string) || ''}
	                          onChange={(e) => updateSelectedNodeData({ urls: e.target.value })}
	                        />
	                      </Form.Item>
	                    )}

	                    {['memory_write', 'knowledge_upsert'].includes(selectedNodeType) && (
	                      <>
	                        {selectedNodeType === 'knowledge_upsert' && (
	                          <Form.Item label="知识库">
	                            <Select
	                              value={(selectedNode.data?.knowledgeBaseId as string) || undefined}
	                              placeholder="选择知识库，未选择时写入默认知识库"
	                              allowClear
	                              onChange={(value) => updateSelectedNodeData({ knowledgeBaseId: value || 'default' })}
	                              options={knowledgeBases.map((base) => ({
	                                value: String(base.id),
	                                label: `${base.name}（${base.documentCount || 0} 文档 / ${base.chunkCount || 0} 分片）`
	                              }))}
	                            />
	                            <div className="workflow-field-hint">
	                              可在顶部“知识库”中创建、导入文本并建立索引。
	                            </div>
	                          </Form.Item>
	                        )}
	                        <Form.Item label={selectedNodeType === 'memory_write' ? '记忆内容' : '知识内容'}>
	                          <Input.TextArea
	                            rows={5}
	                            value={(selectedNode.data?.content as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ content: e.target.value })}
	                          />
	                        </Form.Item>
	                        <Form.Item label="标签">
	                          <Input
	                            placeholder="多个标签用逗号分隔"
	                            value={(selectedNode.data?.tags as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ tags: e.target.value })}
	                          />
	                        </Form.Item>
	                      </>
	                    )}

	                    {['memory_retrieve', 'knowledge_retrieve'].includes(selectedNodeType) && (
	                      <>
	                        {selectedNodeType === 'knowledge_retrieve' && (
	                          <Form.Item label="知识库">
	                            <Select
	                              value={(selectedNode.data?.knowledgeBaseId as string) || undefined}
	                              placeholder="选择要检索的知识库"
	                              allowClear
	                              onChange={(value) => updateSelectedNodeData({ knowledgeBaseId: value || 'default' })}
	                              options={knowledgeBases.map((base) => ({
	                                value: String(base.id),
	                                label: `${base.name}（${base.documentCount || 0} 文档 / ${base.chunkCount || 0} 分片）`
	                              }))}
	                            />
	                          </Form.Item>
	                        )}
	                        <Form.Item label="召回问题">
	                          <Input.TextArea
	                            rows={3}
	                            value={(selectedNode.data?.query as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ query: e.target.value })}
	                          />
	                        </Form.Item>
	                        <Form.Item label="TopK">
	                          <Input
	                            type="number"
	                            min="1"
	                            max="20"
	                            value={(selectedNode.data?.topK as number) || 5}
	                            onChange={(e) => updateSelectedNodeData({ topK: parseInt(e.target.value, 10) || 5 })}
	                          />
	                        </Form.Item>
	                      </>
	                    )}

	                    {selectedNodeType === 'image_generate' && (
	                      <>
	                        <div className="workflow-config-section">
	                          <div className="workflow-config-section-header">
	                            <label className="font-medium text-gray-700">输入配置</label>
	                            <Button
	                              type="dashed"
	                              size="small"
	                              icon={<PlusOutlined />}
	                              onClick={() => addAgentPlanInputParam('prompt')}
	                            >
	                              添加
	                            </Button>
	                          </div>
	                          {getAgentPlanInputParams().map((param, index) => (
	                            <div key={index} className="mb-4 p-3 bg-gray-50 rounded">
	                              <div className="workflow-param-row is-compact">
	                                <Input
	                                  placeholder="参数名"
	                                  value={param.name}
	                                  onChange={(e) => updateAgentPlanInputParam(index, 'name', e.target.value)}
	                                  style={{ width: 120 }}
	                                />
	                                <Select
	                                  value={param.type}
	                                  onChange={(value) => updateAgentPlanInputParam(index, 'type', value)}
	                                  style={{ width: 100 }}
	                                >
	                                  <Select.Option value="input">输入</Select.Option>
	                                  <Select.Option value="reference">引用</Select.Option>
	                                </Select>
	                                <Button
	                                  type="text"
	                                  danger
	                                  icon={<DeleteOutlined />}
	                                  onClick={() => removeAgentPlanInputParam(index)}
	                                />
	                              </div>
	                              <div>
	                                {param.type === 'input' ? (
	                                  <Input.TextArea
	                                    placeholder="输入值"
	                                    value={param.value}
	                                    onChange={(e) => updateAgentPlanInputParam(index, 'value', e.target.value)}
	                                    rows={2}
	                                  />
	                                ) : (
	                                  <Select
	                                    placeholder="选择引用参数"
	                                    value={param.referenceNode}
	                                    onChange={(value) => updateAgentPlanInputParam(index, 'referenceNode', value)}
	                                    className="workflow-reference-select"
	                                    popupMatchSelectWidth={false}
	                                    dropdownStyle={{ minWidth: 360 }}
	                                    style={{ width: '100%' }}
	                                  >
	                                    {getReferenceableParams().map((p) => (
	                                      <Select.Option key={p.value} value={p.value} title={p.label}>
	                                        {p.label}
	                                      </Select.Option>
	                                    ))}
	                                  </Select>
	                                )}
	                              </div>
	                            </div>
	                          ))}
	                          {getAgentPlanInputParams().length === 0 && (
	                            <div className="workflow-config-empty">
	                              暂无输入参数，点击"添加"创建 prompt 引用。
	                            </div>
	                          )}
	                        </div>
	                        <Form.Item label="图片提示词">
	                          <Input.TextArea
	                            rows={5}
	                            placeholder={`例如：生成一张科技媒体风格的公众号封面图，主体是一位年轻程序员坐在双屏电脑前，屏幕上有 AI 工作流节点和代码界面，环境是现代办公室，蓝白色科技感光效，画面干净，高级感，16:9，高清，避免文字、水印、畸形手指。`}
	                            value={(selectedNode.data?.prompt as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ prompt: e.target.value })}
	                          />
	                          <div className="workflow-field-hint">
	                            可写主体、场景、风格、构图、比例、质量要求和负面约束；如果上方输入配置里定义了 prompt 引用，会优先使用引用内容。
	                          </div>
	                        </Form.Item>
	                        <Form.Item label="参考图 URL">
	                          <Input
	                            value={(selectedNode.data?.referenceImageUrl as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ referenceImageUrl: e.target.value })}
	                          />
	                        </Form.Item>
	                        <Form.Item label="尺寸">
	                          {selectedToolProvider === 'step' ? (
	                            <Select
	                              value={(selectedNode.data?.size as string) || getDefaultStepImageSize(selectedImageModel)}
	                              onChange={(value) => updateSelectedNodeData({ size: value })}
	                              options={isStepImageEditModel ? [
	                                { value: '768x1360', label: '768x1360 横版' },
	                                { value: '896x1184', label: '896x1184 横版' },
	                                { value: '1024x1024', label: '1024x1024 方图' },
	                                { value: '1184x896', label: '1184x896 竖版' },
	                                { value: '1360x768', label: '1360x768 竖版' }
	                              ] : [
	                                { value: '1280x800', label: '1280x800 横版' },
	                                { value: '1024x1024', label: '1024x1024 方图' },
	                                { value: '800x1280', label: '800x1280 竖版' },
	                                { value: '768x768', label: '768x768 方图' },
	                                { value: '512x512', label: '512x512 方图' }
	                              ]}
	                            />
	                          ) : (
	                            <Input
	                              placeholder="2K"
	                              value={(selectedNode.data?.size as string) || '2K'}
	                              onChange={(e) => updateSelectedNodeData({ size: e.target.value })}
	                            />
	                          )}
	                        </Form.Item>
	                        {selectedToolProvider === 'step' && (
	                          <>
	                            <Form.Item label="生成步数">
	                              <Input
	                                type="number"
	                                min="1"
	                                max="50"
	                                value={(selectedNode.data?.steps as number) || (isStepImageEditModel ? 8 : 50)}
	                                onChange={(e) => updateSelectedNodeData({ steps: parseInt(e.target.value, 10) || (isStepImageEditModel ? 8 : 50) })}
	                              />
	                            </Form.Item>
	                            <Form.Item label="提示词遵循度">
	                              <Input
	                                type="number"
	                                step="0.1"
	                                min="1"
	                                max="10"
	                                value={(selectedNode.data?.cfgScale as number) || (isStepImageEditModel ? 1 : 7.5)}
	                                onChange={(e) => updateSelectedNodeData({ cfgScale: parseFloat(e.target.value) || (isStepImageEditModel ? 1 : 7.5) })}
	                              />
	                            </Form.Item>
	                            <Form.Item label="随机种子">
	                              <Input
	                                type="number"
	                                placeholder="可选；为空时随机"
	                                value={(selectedNode.data?.seed as number) || undefined}
	                                onChange={(e) => updateSelectedNodeData({ seed: e.target.value ? parseInt(e.target.value, 10) : undefined })}
	                              />
	                            </Form.Item>
	                            {isStepImageEditModel && (
	                              <>
	                                <Form.Item label="文字优化模式">
	                                  <Checkbox
	                                    checked={(selectedNode.data?.textMode as boolean | undefined) ?? true}
	                                    onChange={(e) => updateSelectedNodeData({ textMode: e.target.checked })}
	                                  >
	                                    开启
	                                  </Checkbox>
	                                </Form.Item>
	                                <Form.Item label="负面约束">
	                                  <Input.TextArea
	                                    rows={2}
	                                    placeholder="例如：低清晰度、变形、多余文字、水印、Logo"
	                                    value={(selectedNode.data?.negativePrompt as string) || ''}
	                                    onChange={(e) => updateSelectedNodeData({ negativePrompt: e.target.value })}
	                                  />
	                                </Form.Item>
	                              </>
	                            )}
	                          </>
	                        )}
	                        <div className="workflow-config-section">
	                          <div className="workflow-config-section-header">
	                            <label className="font-medium text-gray-700">输出配置</label>
	                            <Button
	                              type="dashed"
	                              size="small"
	                              icon={<PlusOutlined />}
	                              onClick={() => addAgentPlanOutputParam('image_url', 'imageUrl')}
	                            >
	                              添加
	                            </Button>
	                          </div>
	                          {getAgentPlanOutputParams().map((param, index) => (
	                            <div key={index} className="workflow-param-row">
	                              <Input
	                                placeholder="输出名，如 image_url"
	                                value={param.name}
	                                onChange={(e) => updateAgentPlanOutputParam(index, 'name', e.target.value)}
	                                style={{ flex: 1 }}
	                              />
	                              <Select
	                                placeholder="来源字段"
	                                value={param.value || undefined}
	                                onChange={(value) => updateAgentPlanOutputParam(index, 'value', value)}
	                                style={{ flex: 1 }}
	                                options={getNodeOutputParams('image_generate').map((field) => ({
	                                  value: field,
	                                  label: field
	                                }))}
	                              />
	                              <Button
	                                type="text"
	                                danger
	                                icon={<DeleteOutlined />}
	                                onClick={() => removeAgentPlanOutputParam(index)}
	                              />
	                            </div>
	                          ))}
	                          {getAgentPlanOutputParams().length === 0 && (
	                            <div className="workflow-config-empty">
	                              默认输出 imageUrl、imageUrls、prompt、output；点击"添加"可映射成自定义输出名。
	                            </div>
	                          )}
	                        </div>
	                      </>
	                    )}

	                    {selectedNodeType === 'video_generate' && (
	                      <>
	                        <div className="workflow-config-section">
	                          <div className="workflow-config-section-header">
	                            <label className="font-medium text-gray-700">输入配置</label>
	                            <Button
	                              type="dashed"
	                              size="small"
	                              icon={<PlusOutlined />}
	                              onClick={() => addAgentPlanInputParam('prompt')}
	                            >
	                              添加
	                            </Button>
	                          </div>
	                          {getAgentPlanInputParams().map((param, index) => (
	                            <div key={index} className="mb-4 p-3 bg-gray-50 rounded">
	                              <div className="workflow-param-row is-compact">
	                                <Input
	                                  placeholder="参数名"
	                                  value={param.name}
	                                  onChange={(e) => updateAgentPlanInputParam(index, 'name', e.target.value)}
	                                  style={{ width: 120 }}
	                                />
	                                <Select
	                                  value={param.type}
	                                  onChange={(value) => updateAgentPlanInputParam(index, 'type', value)}
	                                  style={{ width: 100 }}
	                                >
	                                  <Select.Option value="input">输入</Select.Option>
	                                  <Select.Option value="reference">引用</Select.Option>
	                                </Select>
	                                <Button
	                                  type="text"
	                                  danger
	                                  icon={<DeleteOutlined />}
	                                  onClick={() => removeAgentPlanInputParam(index)}
	                                />
	                              </div>
	                              <div>
	                                {param.type === 'input' ? (
	                                  <Input.TextArea
	                                    placeholder="输入值"
	                                    value={param.value}
	                                    onChange={(e) => updateAgentPlanInputParam(index, 'value', e.target.value)}
	                                    rows={2}
	                                  />
	                                ) : (
	                                  <Select
	                                    placeholder="选择引用参数"
	                                    value={param.referenceNode}
	                                    onChange={(value) => updateAgentPlanInputParam(index, 'referenceNode', value)}
	                                    className="workflow-reference-select"
	                                    popupMatchSelectWidth={false}
	                                    dropdownStyle={{ minWidth: 360 }}
	                                    style={{ width: '100%' }}
	                                  >
	                                    {getReferenceableParams().map((p) => (
	                                      <Select.Option key={p.value} value={p.value} title={p.label}>
	                                        {p.label}
	                                      </Select.Option>
	                                    ))}
	                                  </Select>
	                                )}
	                              </div>
	                            </div>
	                          ))}
	                          {getAgentPlanInputParams().length === 0 && (
	                            <div className="workflow-config-empty">
	                              暂无输入参数，点击"添加"创建 prompt 引用。
	                            </div>
	                          )}
	                        </div>
	                        <Form.Item label="视频提示词">
	                          <Input.TextArea
	                            rows={5}
	                            placeholder="例如：一位程序员在现代办公室内操作 AI 工作流系统，屏幕上节点流转发光，镜头缓慢推进，真实摄影风格，柔和自然光，画面稳定，高清，无文字水印。"
	                            value={(selectedNode.data?.prompt as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ prompt: e.target.value })}
	                          />
	                          <div className="workflow-field-hint">
	                            如果上方输入配置里定义了 prompt 引用，会优先使用引用内容。
	                          </div>
	                        </Form.Item>
	                        <Form.Item label="首帧/参考图 URL">
	                          <Input
	                            value={(selectedNode.data?.referenceImageUrl as string) || ''}
	                            onChange={(e) => updateSelectedNodeData({ referenceImageUrl: e.target.value })}
	                          />
	                        </Form.Item>
	                        <Form.Item label="时长(秒)">
	                          <Input
	                            type="number"
	                            value={(selectedNode.data?.duration as number) || 5}
	                            onChange={(e) => updateSelectedNodeData({ duration: parseInt(e.target.value, 10) || 5 })}
	                          />
	                        </Form.Item>
	                        <div className="workflow-config-section">
	                          <div className="workflow-config-section-header">
	                            <label className="font-medium text-gray-700">输出配置</label>
	                            <Button
	                              type="dashed"
	                              size="small"
	                              icon={<PlusOutlined />}
	                              onClick={() => addAgentPlanOutputParam('video_url', 'videoUrl')}
	                            >
	                              添加
	                            </Button>
	                          </div>
	                          {getAgentPlanOutputParams().map((param, index) => (
	                            <div key={index} className="workflow-param-row">
	                              <Input
	                                placeholder="输出名，如 video_url"
	                                value={param.name}
	                                onChange={(e) => updateAgentPlanOutputParam(index, 'name', e.target.value)}
	                                style={{ flex: 1 }}
	                              />
	                              <Select
	                                placeholder="来源字段"
	                                value={param.value || undefined}
	                                onChange={(value) => updateAgentPlanOutputParam(index, 'value', value)}
	                                style={{ flex: 1 }}
	                                options={getNodeOutputParams('video_generate').map((field) => ({
	                                  value: field,
	                                  label: field
	                                }))}
	                              />
	                              <Button
	                                type="text"
	                                danger
	                                icon={<DeleteOutlined />}
	                                onClick={() => removeAgentPlanOutputParam(index)}
	                              />
	                            </div>
	                          ))}
	                          {getAgentPlanOutputParams().length === 0 && (
	                            <div className="workflow-config-empty">
	                              默认输出 videoUrl、coverUrl、taskId、status、output；点击"添加"可映射成自定义输出名。
	                            </div>
	                          )}
	                        </div>
	                      </>
	                    )}

                  </Form>
                )}

                {/* 条件分支节点配置 */}
                {selectedNodeType === 'condition' && (
                  <Form layout="vertical" className="workflow-config-form">
                    <Alert
                      className="workflow-form-example"
                      type="info"
                      showIcon
                      message="示例：判断用户是不是要转人工"
                      description={'规则按顺序从上到下判断，第一条命中的规则走它自己的分支；都不命中就走"否则(默认)"分支。比如加一条：字段填 input，操作符选"包含"，值填 转人工——命中时从画布上这条规则对应的连接点（按顺序编号，和下面列表顺序一致）拉一条线到你的转人工节点，默认连接点再连去正常问答节点。'}
                    />
                    <div className="workflow-config-section">
                      <div className="workflow-config-section-header">
                        <label className="font-medium text-gray-700">条件规则</label>
                        <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addConditionRule}>
                          添加条件
                        </Button>
                      </div>
                      {getConditionRules().map((rule, index) => {
                        const operatorMeta = CONDITION_OPERATORS.find((o) => o.value === rule.operator);
                        return (
                          <div key={rule.id} className="mb-3 p-3 bg-gray-50 rounded">
                            <div className="workflow-param-row is-compact" style={{ marginBottom: 8 }}>
                              <span className="condition-badge">{index + 1}</span>
                              <Input
                                placeholder="字段，例如 input"
                                value={rule.field}
                                onChange={(e) => updateConditionRule(index, { field: e.target.value })}
                                style={{ width: 110 }}
                              />
                              <Select
                                value={rule.operator}
                                options={CONDITION_OPERATORS.map((o) => ({ value: o.value, label: o.label }))}
                                onChange={(value) => updateConditionRule(index, { operator: value })}
                                style={{ width: 130 }}
                              />
                              {operatorMeta?.needsValue !== false && (
                                <Input
                                  placeholder="值，例如 转人工"
                                  value={rule.value}
                                  onChange={(e) => updateConditionRule(index, { value: e.target.value })}
                                  style={{ flex: 1 }}
                                />
                              )}
                              <Button
                                type="text"
                                danger
                                icon={<DeleteOutlined />}
                                onClick={() => removeConditionRule(index)}
                              />
                            </div>
                          </div>
                        );
                      })}
                      {getConditionRules().length === 0 && (
                        <div className="workflow-config-empty">
                          还没有配置任何条件，所有输入都会走"否则(默认)"分支。点击"添加条件"创建第一条规则。
                        </div>
                      )}
                      <div className="workflow-field-hint">
                        字段支持从上游节点输出里取值，比如 input；条件从上到下依次判断，命中即停止，不再看后面的规则。
                      </div>
                    </div>
                  </Form>
                )}

	                {/* 其他节点配置 */}
	                {selectedNode.data?.type !== 'input' &&
	                 selectedNode.data?.type !== 'output' &&
	                 !isLlmNodeType(selectedNodeType) &&
	                 selectedNode.data?.type !== 'tts' &&
	                 selectedNodeType !== 'condition' &&
	                 !isAgentPlanNode && (
                  <div className="workflow-config-empty">
                    该节点暂无可配置项
                  </div>
                )}
              </div>
            ) : (
              <div className="workflow-empty-inspector">
                <div className="workflow-empty-icon">+</div>
                <p>未选择节点</p>
              </div>
            )}
        </aside>
      </div>

      {/* 调试抽屉 */}
      <DebugDrawer
        open={debugDrawerOpen}
        workflowId={activeWorkflowId}
        totalNodeCount={nodes.length}
        onClose={() => setDebugDrawerOpen(false)}
        onExecute={handleExecute}
      />

      {/* 加载工作流对话框 */}
      <Modal
        title="加载工作流"
        open={loadModalOpen}
        onCancel={() => setLoadModalOpen(false)}
        footer={null}
        width={600}
      >
        <List
          loading={loadingWorkflows}
          dataSource={workflows}
          renderItem={(workflow) => (
            <List.Item
              key={workflow.id}
              actions={[
                <Button type="link" onClick={() => handleLoadWorkflow(workflow)}>
                  加载
                </Button>,
                <Popconfirm
                  title="删除工作流"
                  description={`确定删除「${workflow.name}」吗?`}
                  okText="删除"
                  cancelText="取消"
                  okButtonProps={{ danger: true, loading: deletingWorkflowId === workflow.id }}
                  onConfirm={() => handleDeleteWorkflow(workflow)}
                >
                  <Button
                    danger
                    type="link"
                    icon={<DeleteOutlined />}
                    loading={deletingWorkflowId === workflow.id}
                  >
                    删除
                  </Button>
                </Popconfirm>
              ]}
            >
              <List.Item.Meta
                title={workflow.name}
                description={`创建于: ${new Date(workflow.createdAt).toLocaleString()}`}
              />
            </List.Item>
          )}
        />
      </Modal>
    </div>
  );
};

export default EditorPage;
