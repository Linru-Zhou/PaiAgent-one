import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, List, Modal, Popconfirm, Space, Tag, Typography, message } from 'antd';
import { ApiOutlined, ArrowLeftOutlined, DeleteOutlined, PlusOutlined, SearchOutlined, ToolOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  McpToolConfig,
  createAgentPlanWebSearchMcp,
  createMcpTool,
  deleteMcpTool,
  getMcpTools,
  testMcpTool,
} from '../api/mcpTools';

interface CustomMcpFormValues {
  name: string;
  description?: string;
  toolName: string;
  command: string;
  args?: string;
  env?: string;
}

const CUSTOM_MCP_EXAMPLE: CustomMcpFormValues = {
  name: '文件系统 MCP',
  description: '示例：通过官方 filesystem MCP server 读写指定目录下的文件',
  toolName: 'filesystem',
  command: 'npx',
  args: '-y\n@modelcontextprotocol/server-filesystem\n/Users/you/shared-folder',
  env: 'LOG_LEVEL=info',
};

const getErrorMessage = (error: unknown, fallback: string): string => {
  if (error && typeof error === 'object' && 'response' in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response;
    if (response?.data?.message) {
      return response.data.message;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
};

interface WebSearchResultItem {
  title?: string;
  siteName?: string;
  url?: string;
  snippet?: string;
  summary?: string;
  sortId?: number;
}

const McpToolPage = () => {
  const navigate = useNavigate();
  const [tools, setTools] = useState<McpToolConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [customOpen, setCustomOpen] = useState(false);
  const [customLoading, setCustomLoading] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [testingTool, setTestingTool] = useState<McpToolConfig | null>(null);
  const [testResult, setTestResult] = useState<Record<string, unknown> | null>(null);
  const [testLoading, setTestLoading] = useState(false);
  const [createForm] = Form.useForm();
  const [customForm] = Form.useForm<CustomMcpFormValues>();
  const [testForm] = Form.useForm();

  useEffect(() => {
    loadTools();
  }, []);

  const loadTools = async () => {
    setLoading(true);
    try {
      const result = await getMcpTools();
      if (result.code === 200) {
        setTools(result.data);
      }
    } catch (error) {
      message.error(`加载 MCP 工具失败: ${error instanceof Error ? error.message : '网络错误'}`);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateAgentPlanSearch = async () => {
    const values = await createForm.validateFields();
    try {
      const result = await createAgentPlanWebSearchMcp(values);
      if (result.code === 200) {
        message.success('Agent Plan 联网搜索 MCP 已添加');
        setCreateOpen(false);
        createForm.resetFields();
        await loadTools();
      } else {
        message.error(result.message || '添加失败');
      }
    } catch (error) {
      message.error(getErrorMessage(error, '添加失败，请稍后重试'));
    }
  };

  const handleCreateCustomTool = async () => {
    const values = await customForm.validateFields();
    setCustomLoading(true);
    try {
      const args = (values.args || '')
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean);
      const env: Record<string, string> = {};
      (values.env || '')
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .forEach((line) => {
          const idx = line.indexOf('=');
          if (idx > 0) {
            env[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
          }
        });

      const result = await createMcpTool({
        name: values.name,
        description: values.description,
        toolType: 'custom',
        toolName: values.toolName,
        transport: 'stdio',
        command: values.command,
        args,
        env,
      });
      if (result.code === 200) {
        message.success('自定义 MCP 工具已保存');
        setCustomOpen(false);
        customForm.resetFields();
        await loadTools();
      } else {
        message.error(result.message || '添加失败');
      }
    } catch (error) {
      message.error(getErrorMessage(error, '添加失败，请稍后重试'));
    } finally {
      setCustomLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const result = await deleteMcpTool(id);
      if (result.code === 200) {
        message.success('MCP 工具已删除');
        await loadTools();
      } else {
        message.error(result.message || '删除失败');
      }
    } catch (error) {
      message.error(getErrorMessage(error, '删除失败，请稍后重试'));
    }
  };

  const handleOpenTest = (tool: McpToolConfig) => {
    setTestingTool(tool);
    setTestResult(null);
    testForm.setFieldsValue({ query: '沉默王二 人物 外貌' });
    setTestOpen(true);
  };

  const handleTest = async () => {
    if (!testingTool) return;
    const values = await testForm.validateFields();
    setTestLoading(true);
    try {
      const result = await testMcpTool(testingTool.id, values);
      if (result.code === 200) {
        setTestResult(result.data);
        message.success('MCP 工具调用成功');
      } else {
        message.error(result.message || 'MCP 工具调用失败');
      }
    } catch (error) {
      message.error(getErrorMessage(error, 'MCP 工具调用失败，请稍后重试'));
    } finally {
      setTestLoading(false);
    }
  };

  const getWebResults = (result: Record<string, unknown> | null): WebSearchResultItem[] => {
    if (!result || !Array.isArray(result.webResults)) {
      return [];
    }
    return result.webResults
      .filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object')
      .map((item) => ({
        title: String(item.title || ''),
        siteName: String(item.siteName || ''),
        url: String(item.url || ''),
        snippet: String(item.snippet || ''),
        summary: String(item.summary || ''),
        sortId: typeof item.sortId === 'number' ? item.sortId : undefined,
      }));
  };

  const truncateText = (text: string, maxLength = 360) => {
    if (!text || text.length <= maxLength) return text;
    return `${text.slice(0, maxLength)}...`;
  };

  const renderTestResult = () => {
    if (!testResult) return null;

    const webResults = getWebResults(testResult);
    const requestId = typeof testResult.requestId === 'string' ? testResult.requestId : '';
    const resultCount = typeof testResult.resultCount === 'number' ? testResult.resultCount : webResults.length;

    return (
      <div className="mcp-test-result">
        <div className="mcp-test-summary">
          <Space wrap>
            <Tag color="success">调用成功</Tag>
            <span>返回 {resultCount} 条结果</span>
            {requestId && <Typography.Text type="secondary">RequestId: {requestId}</Typography.Text>}
          </Space>
        </div>

        {webResults.length > 0 ? (
          <List
            className="mcp-test-result-list"
            dataSource={webResults}
            renderItem={(item, index) => (
              <List.Item>
                <List.Item.Meta
                  title={(
                    <Space size={8} wrap>
                      <Tag>{item.sortId || index + 1}</Tag>
                      {item.url ? (
                        <Typography.Link href={item.url} target="_blank" rel="noreferrer">
                          {item.title || item.url}
                        </Typography.Link>
                      ) : (
                        <span>{item.title || '未命名结果'}</span>
                      )}
                      {item.siteName && <Tag color="blue">{item.siteName}</Tag>}
                    </Space>
                  )}
                  description={(
                    <Space direction="vertical" size={4}>
                      {item.url && <Typography.Text type="secondary">{item.url}</Typography.Text>}
                      <Typography.Paragraph className="mcp-test-snippet">
                        {truncateText(item.summary || item.snippet || '没有摘要')}
                      </Typography.Paragraph>
                    </Space>
                  )}
                />
              </List.Item>
            )}
          />
        ) : (
          <Typography.Paragraph className="mcp-test-snippet">
            {typeof testResult.summary === 'string' ? testResult.summary : '没有可展示的搜索结果'}
          </Typography.Paragraph>
        )}

        <details className="mcp-test-raw">
          <summary>查看原始返回</summary>
          <pre>{JSON.stringify(testResult, null, 2)}</pre>
        </details>
      </div>
    );
  };

  return (
    <div className="knowledge-shell">
      <header className="knowledge-topbar">
        <div className="knowledge-title-group">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/editor')}>工作流</Button>
          <div>
            <div className="knowledge-kicker">Resource</div>
            <h2>MCP 工具</h2>
          </div>
        </div>
        <Space>
          <Button icon={<ToolOutlined />} onClick={() => { customForm.resetFields(); setCustomOpen(true); }}>
            添加自定义 MCP 工具
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            添加 Agent Plan 联网搜索
          </Button>
        </Space>
      </header>

      <div className="knowledge-layout">
        <aside className="knowledge-sidebar">
          <List
            loading={loading}
            dataSource={tools}
            renderItem={(tool) => (
              <List.Item className="knowledge-base-item">
                <div className="knowledge-base-icon"><ApiOutlined /></div>
                <div className="knowledge-base-body">
                  <div className="knowledge-base-name">{tool.name}</div>
                  <div className="knowledge-muted">{tool.toolName} · {tool.transport}</div>
                </div>
                <Tag color={tool.enabled === 1 ? 'success' : 'default'}>{tool.enabled === 1 ? '启用' : '停用'}</Tag>
              </List.Item>
            )}
          />
        </aside>

        <main className="knowledge-main">
          <div className="knowledge-panel">
            <div className="knowledge-panel-header">
              <div>
                <div className="knowledge-kicker">MCP Runtime</div>
                <h3>工具配置</h3>
              </div>
            </div>
            <List
              dataSource={tools}
              locale={{ emptyText: '还没有 MCP 工具，先添加 Agent Plan 联网搜索' }}
              renderItem={(tool) => {
                const canTest = tool.toolName === 'web_search';
                return (
                <List.Item
                  actions={[
                    <Button
                      key="test"
                      icon={<SearchOutlined />}
                      disabled={!canTest}
                      title={canTest ? undefined : '当前执行引擎只接入了联网搜索（web_search）工具的调用，其他工具暂时只能保存配置，还不能在这里试用'}
                      onClick={() => handleOpenTest(tool)}
                    >
                      测试
                    </Button>,
                    <Popconfirm key="delete" title="删除这个 MCP 工具？" onConfirm={() => handleDelete(tool.id)}>
                      <Button danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={<div className="knowledge-base-icon"><ApiOutlined /></div>}
                    title={(
                      <Space>
                        <span>{tool.name}</span>
                        <Tag>{tool.toolType}</Tag>
                        {tool.preset === 1 && <Tag color="blue">预设</Tag>}
                        {!canTest && <Tag color="warning">暂未接入执行引擎</Tag>}
                      </Space>
                    )}
                    description={(
                      <Space direction="vertical" size={4}>
                        <span>{tool.description || '未填写描述'}</span>
                        <Typography.Text code>{tool.command} {tool.args.join(' ')}</Typography.Text>
                        <span className="knowledge-muted">
                          Env: {Object.keys(tool.env || {}).join(', ') || '无'}
                        </span>
                      </Space>
                    )}
                  />
                </List.Item>
                );
              }}
            />
          </div>
        </main>
      </div>

      <Modal
        title="添加 Agent Plan 联网搜索 MCP"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreateAgentPlanSearch}
        okText="添加"
        width={640}
      >
        <Alert
          className="mcp-form-example"
          type="info"
          showIcon
          message="示例"
          description="名称随便起，比如「团队联网搜索」；API Key 在火山方舟控制台 → Agent Plan → Harness 配置里领取，格式类似一长串字母数字。添加后就可以在「大模型」节点的技能/工具里勾选联网搜索使用。"
        />
        <Form form={createForm} layout="vertical" initialValues={{ name: 'Agent Plan 联网搜索' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：团队联网搜索" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选，用于区分不同 Agent Plan key 或额度来源" />
          </Form.Item>
          <Form.Item name="apiKey" label="联网搜索 API Key" rules={[{ required: true, message: '请输入联网搜索 API Key' }]}>
            <Input.Password placeholder="来自 Agent Plan 使用配置 / 配置 Harness 的联网搜索 API Key" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="添加自定义 MCP 工具"
        open={customOpen}
        onCancel={() => setCustomOpen(false)}
        onOk={handleCreateCustomTool}
        okText="保存"
        confirmLoading={customLoading}
        width={680}
      >
        <Alert
          className="mcp-form-example"
          type="warning"
          showIcon
          message="先看这里"
          description="这个表单可以保存任意 stdio 协议的 MCP Server 配置（比如官方的 filesystem、github 等），但目前工作流的执行引擎只内置了对「联网搜索」这一种工具的实际调用——其他工具存下来之后，暂时还不能在工作流节点里被调用或在这里点「测试」，属于预留的扩展位。点下面的按钮可以填入一个真实可跑的示例（本地文件系统 MCP）感受一下格式。"
        />
        <Space style={{ marginBottom: 12 }}>
          <Button size="small" onClick={() => customForm.setFieldsValue(CUSTOM_MCP_EXAMPLE)}>
            填入示例
          </Button>
        </Space>
        <Form form={customForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：文件系统 MCP" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="这个工具是做什么用的，方便以后自己认得" />
          </Form.Item>
          <Form.Item
            name="toolName"
            label="工具名（toolName）"
            rules={[{ required: true, message: '请输入工具名' }]}
            extra="暴露给 Agent 的工具标识，例如 filesystem、github"
          >
            <Input placeholder="例如：filesystem" />
          </Form.Item>
          <Form.Item
            name="command"
            label="启动命令"
            rules={[{ required: true, message: '请输入启动命令' }]}
            extra="大多数 MCP Server 用 npx 或 uvx 启动，需要本机已安装 Node.js / uv"
          >
            <Input placeholder="例如：npx" />
          </Form.Item>
          <Form.Item
            name="args"
            label="启动参数"
            extra="每行一个参数，会按顺序拼到启动命令后面"
          >
            <Input.TextArea rows={3} placeholder={'例如：\n-y\n@modelcontextprotocol/server-filesystem\n/Users/you/shared-folder'} />
          </Form.Item>
          <Form.Item
            name="env"
            label="环境变量"
            extra="每行一个 KEY=VALUE，比如需要传 API Key 时用这里，而不是写进参数里"
          >
            <Input.TextArea rows={2} placeholder={'例如：\nGITHUB_TOKEN=ghp_xxxx'} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`测试 ${testingTool?.name || ''}`}
        open={testOpen}
        onCancel={() => setTestOpen(false)}
        onOk={handleTest}
        okText="调用"
        confirmLoading={testLoading}
        width={860}
      >
        <Form form={testForm} layout="vertical">
          <Form.Item name="query" label="搜索 Query" rules={[{ required: true, message: '请输入搜索 Query' }]}>
            <Input />
          </Form.Item>
        </Form>
        {renderTestResult()}
      </Modal>
    </div>
  );
};

export default McpToolPage;
