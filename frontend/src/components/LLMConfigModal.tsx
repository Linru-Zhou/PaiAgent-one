import React, { useEffect, useState } from 'react';
import { Alert, Modal, Form, Input, AutoComplete, Checkbox, Select, Button, Table, Space, message, Popconfirm } from 'antd';
import { SettingOutlined, PlusOutlined, EditOutlined, DeleteOutlined, CloseOutlined } from '@ant-design/icons';
import { useLLMConfigStore } from '../store/llmConfigStore';
import { LLMGlobalConfig, LLMConfigRequest } from '../api/llmConfig';
import { getProviderLabel, normalizeProviderKey } from '../utils/provider';

const PROVIDERS = [
  { value: 'openai', label: 'OpenAI' },
  { value: 'deepseek', label: 'DeepSeek' },
  { value: 'qwen', label: '通义千问' },
  { value: 'step', label: '阶跃星辰' },
  { value: 'apifree', label: 'APIFree / SkyClaw' },
  { value: 'volcengine_agent_plan', label: '火山方舟 Agent Plan' }
];

type CapabilityKey = 'tts' | 'embedding' | 'memory' | 'image' | 'video';

const CAPABILITIES: Array<{
  key: CapabilityKey;
  label: string;
  description: string;
}> = [
  { key: 'tts', label: 'TTS 模型', description: '给音频合成节点提供默认语音模型。' },
  { key: 'embedding', label: '向量模型', description: '用于知识库索引、RAG 和记忆召回。' },
  { key: 'memory', label: '记忆能力', description: '给 Agent 启用跨轮次上下文召回。' },
  { key: 'image', label: '图片生成', description: '给图片生成工具提供默认模型。' },
  { key: 'video', label: '视频生成', description: '给视频生成工具提供默认模型。' }
];

interface LLMConfigModalProps {
  visible?: boolean;
  onClose?: () => void;
}

const LLMConfigModal: React.FC<LLMConfigModalProps> = ({ visible, onClose }) => {
  const [modalVisible, setModalVisible] = useState(visible || false);
  const [formVisible, setFormVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState<LLMGlobalConfig | null>(null);
  const [activeCapabilities, setActiveCapabilities] = useState<CapabilityKey[]>([]);
  const [capabilityPickerKey, setCapabilityPickerKey] = useState(0);
  const [form] = Form.useForm();

  const {
    configs,
    loading,
    error,
    fetchAllConfigs,
    saveConfig,
    deleteConfig,
    clearError
  } = useLLMConfigStore();

  const providerOptions = Array.from(
    new Map(
      [...PROVIDERS, ...configs.map((config) => ({
        value: normalizeProviderKey(config.provider),
        label: getProviderLabel(config.provider)
      }))]
        .map((item) => [normalizeProviderKey(item.value), item])
    ).values()
  ).map((item) => ({
    value: normalizeProviderKey(item.value),
    label: item.label
  }));

  useEffect(() => {
    if (modalVisible) {
      fetchAllConfigs();
    }
  }, [modalVisible]);

  useEffect(() => {
    if (visible !== undefined) {
      setModalVisible(visible);
    }
  }, [visible]);

  useEffect(() => {
    if (error) {
      message.error(error);
      clearError();
    }
  }, [error]);

  const handleOpenModal = () => {
    setModalVisible(true);
  };

  const handleCloseModal = () => {
    setModalVisible(false);
    setFormVisible(false);
    setEditingConfig(null);
    form.resetFields();
    onClose?.();
  };

  const handleOpenForm = (config?: LLMGlobalConfig) => {
    setEditingConfig(config || null);
    setFormVisible(true);
    if (config) {
      setActiveCapabilities(getCapabilitiesFromConfig(config));
      form.setFieldsValue({
        provider: config.provider,
        apiUrl: config.apiUrl,
        apiKey: config.apiKey,
        model: config.model,
        ttsModel: config.ttsModel,
        embeddingModel: config.embeddingModel,
        imageModel: config.imageModel,
        videoModel: config.videoModel,
        memoryEnabled: config.memoryEnabled === 1
      });
    } else {
      setActiveCapabilities([]);
      form.resetFields();
      form.setFieldsValue({
        memoryEnabled: false
      });
    }
  };

  const handleCloseForm = () => {
    setFormVisible(false);
    setEditingConfig(null);
    setActiveCapabilities([]);
    form.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const provider = normalizeProviderKey(values.provider);
      const request: LLMConfigRequest = {
        id: editingConfig?.id,
        provider,
        configName: editingConfig?.configName || buildInternalConfigName(provider, values.model, values.ttsModel),
        apiUrl: values.apiUrl,
        apiKey: values.apiKey,
        model: values.model,
        ttsModel: hasCapability('tts') ? values.ttsModel : undefined,
        embeddingModel: hasCapability('embedding') ? values.embeddingModel : undefined,
        imageModel: hasCapability('image') ? values.imageModel : undefined,
        videoModel: hasCapability('video') ? values.videoModel : undefined,
        memoryEnabled: hasCapability('memory') && values.memoryEnabled ? 1 : 0
      };

      const result = await saveConfig(request);
      if (result) {
        message.success(editingConfig ? '配置已更新' : '配置已创建');
        handleCloseForm();
      }
    } catch (error) {
      console.error('Form validation failed:', error);
    }
  };

  const buildInternalConfigName = (provider: string, model?: string, ttsModel?: string) => {
    const modelPart = (model || ttsModel || 'model').trim().replace(/\s+/g, '-');
    return `${provider}-${modelPart}-${Date.now()}`;
  };

  const getCapabilitiesFromConfig = (config: LLMGlobalConfig): CapabilityKey[] => {
    const capabilities: CapabilityKey[] = [];
    if (config.ttsModel) capabilities.push('tts');
    if (config.embeddingModel) capabilities.push('embedding');
    if (config.memoryEnabled === 1) capabilities.push('memory');
    if (config.imageModel) capabilities.push('image');
    if (config.videoModel) capabilities.push('video');
    return capabilities;
  };

  const hasCapability = (key: CapabilityKey) => activeCapabilities.includes(key);

  const addCapability = (key?: CapabilityKey) => {
    if (!key || hasCapability(key)) return;
    setActiveCapabilities((current) => [...current, key]);
    setCapabilityPickerKey((current) => current + 1);

    if (key === 'embedding' && !form.getFieldValue('embeddingModel')) {
      form.setFieldValue('embeddingModel', 'doubao-embedding-vision');
    }
    if (key === 'memory') {
      form.setFieldValue('memoryEnabled', true);
      if (!form.getFieldValue('embeddingModel')) {
        form.setFieldValue('embeddingModel', 'doubao-embedding-vision');
      }
    }
  };

  const removeCapability = (key: CapabilityKey) => {
    setActiveCapabilities((current) => current.filter((item) => item !== key));

    if (key === 'tts') form.setFieldValue('ttsModel', undefined);
    if (key === 'embedding') form.setFieldValue('embeddingModel', undefined);
    if (key === 'image') form.setFieldValue('imageModel', undefined);
    if (key === 'video') form.setFieldValue('videoModel', undefined);
    if (key === 'memory') form.setFieldValue('memoryEnabled', false);
  };

  const renderCapabilityField = (key: CapabilityKey) => {
    const definition = CAPABILITIES.find((item) => item.key === key);
    if (!definition) return null;

    return (
      <div className="config-capability-row" key={key}>
        <div className="config-capability-head">
          <div>
            <div className="config-capability-title">{definition.label}</div>
            <div className="config-capability-desc">{definition.description}</div>
          </div>
          <Button
            type="text"
            size="small"
            icon={<CloseOutlined />}
            onClick={() => removeCapability(key)}
            title={`移除${definition.label}`}
          />
        </div>
        {key === 'tts' && (
          <Form.Item name="ttsModel" label="默认 TTS 模型" rules={[{ required: true, message: '请输入 TTS 模型' }]}>
            <Input placeholder="例如: qwen3-tts-flash, qwen-audio-3.1-tts-flash, stepaudio-2.5-tts" />
          </Form.Item>
        )}
        {key === 'embedding' && (
          <Form.Item name="embeddingModel" label="默认向量模型" rules={[{ required: true, message: '请输入向量模型' }]}>
            <Input placeholder="doubao-embedding-vision" />
          </Form.Item>
        )}
        {key === 'memory' && (
          <Form.Item name="memoryEnabled" valuePropName="checked" className="config-capability-switch">
            <Checkbox>启用记忆能力</Checkbox>
          </Form.Item>
        )}
        {key === 'image' && (
          <Form.Item name="imageModel" label="默认图片模型" rules={[{ required: true, message: '请输入图片模型' }]}>
            <Input placeholder="例如: wan2.2-t2i-flash（通义万相）, step-image-edit-2, step-1x-medium, seedream 相关图片生成模型" />
          </Form.Item>
        )}
        {key === 'video' && (
          <Form.Item name="videoModel" label="默认视频模型" rules={[{ required: true, message: '请输入视频模型' }]}>
            <Input placeholder="例如: wan2.2-t2v-plus（通义万相）, seedance 相关视频生成模型" />
          </Form.Item>
        )}
      </div>
    );
  };

  const getCapabilitySummary = (record: LLMGlobalConfig) => [
    record.ttsModel ? `TTS: ${record.ttsModel}` : '',
    record.embeddingModel ? `向量: ${record.embeddingModel}` : '',
    record.memoryEnabled === 1 ? '记忆' : '',
    record.imageModel ? `生图: ${record.imageModel}` : '',
    record.videoModel ? `生视频: ${record.videoModel}` : ''
  ].filter(Boolean).join(' / ') || '-';

  const handleDelete = async (id: number) => {
    const success = await deleteConfig(id);
    if (success) {
      message.success('配置已删除');
    }
  };

  const columns = [
    {
      title: '提供商',
      dataIndex: 'provider',
      key: 'provider',
      ellipsis: true,
      width: 88,
      render: (provider: string) => {
        return getProviderLabel(provider);
      }
    },
    {
      title: 'API 地址',
      dataIndex: 'apiUrl',
      key: 'apiUrl',
      ellipsis: true,
      width: 210
    },
    {
      title: '模型',
      dataIndex: 'model',
      key: 'model',
      width: 170
    },
    {
      title: '能力',
      key: 'capabilities',
      width: 300,
      ellipsis: true,
      render: (_: any, record: LLMGlobalConfig) => getCapabilitySummary(record)
    },
    {
      title: '操作',
      key: 'action',
      width: 96,
      render: (_: any, record: LLMGlobalConfig) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleOpenForm(record)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定要删除此配置吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <>
      {!visible && (
        <Button
          icon={<SettingOutlined />}
          onClick={handleOpenModal}
          title="模型配置管理"
        >
          模型管理
        </Button>
      )}

      <Modal
        title="全局模型配置管理"
        open={modalVisible}
        onCancel={handleCloseModal}
        footer={null}
        width={1100}
        style={{ maxWidth: 'calc(100vw - 32px)' }}
      >
        <div className="mb-4">
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => handleOpenForm()}
          >
            新增配置
          </Button>
          <div className="text-xs text-gray-500 mt-2">
            每条配置保存一组 API 地址、密钥和模型能力；联网搜索请在“MCP 工具”中添加 Agent Plan Harness 工具。
          </div>
        </div>

        <Table
          columns={columns}
          dataSource={configs}
          rowKey="id"
          loading={loading}
          pagination={false}
          size="small"
          tableLayout="fixed"
          scroll={{ x: 1080 }}
        />

        <Modal
          title={editingConfig ? '编辑配置' : '新增配置'}
          open={formVisible}
          onCancel={handleCloseForm}
          onOk={handleSubmit}
          confirmLoading={loading}
          okText="保存"
          cancelText="取消"
          width={680}
        >
          <Form
            form={form}
            layout="vertical"
            autoComplete="off"
          >
            <Form.Item
              name="provider"
              label="提供商"
              rules={[
                { required: true, message: '请输入或选择提供商' },
                {
                  validator: (_, value) =>
                    value?.trim()
                      ? Promise.resolve()
                      : Promise.reject(new Error('请输入或选择提供商'))
                }
              ]}
              extra="支持直接手填，也可以从已有供应商中选择。"
            >
              <AutoComplete
                placeholder="请输入或选择提供商"
                options={providerOptions}
                disabled={!!editingConfig}
                filterOption={(inputValue, option) =>
                  (option?.value ?? '').toLowerCase().includes(inputValue.toLowerCase()) ||
                  String(option?.label ?? '').toLowerCase().includes(inputValue.toLowerCase())
                }
              />
            </Form.Item>

            <Form.Item
              name="apiUrl"
              label="API 地址"
              rules={[{ required: true, message: '请输入 API 地址' }]}
            >
              <Input placeholder="例如: https://api.openai.com, https://api.stepfun.com/v1" />
            </Form.Item>
            <Form.Item noStyle shouldUpdate={(prev, next) => prev.provider !== next.provider || prev.ttsModel !== next.ttsModel}>
              {({ getFieldValue }) => /^qwen-audio/i.test(String(getFieldValue('ttsModel') || '')) ? (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message="qwen-audio-3.x 系列走 WebSocket 接口"
                  description="API 地址填 wss://<业务空间ID>.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference（业务空间 ID 在百炼控制台查看）；也可以只填 https://dashscope.aliyuncs.com/api/v1，会自动转成对应的 wss 地址。音色是 longanhuan_v3.1 这类字符串 ID，在节点的音色里选择。"
                />
              ) : null}
            </Form.Item>

            <Form.Item
              name="apiKey"
              label="API 密钥"
              rules={[{ required: true, message: '请输入 API 密钥' }]}
            >
              <Input.Password placeholder="输入 API 密钥" />
            </Form.Item>

            <Form.Item
              name="model"
              label="默认 LLM 模型"
              rules={[{ required: true, message: '请输入默认模型' }]}
            >
              <Input placeholder="例如: gpt-4.1, deepseek-chat, step-3.5-flash-2603" />
            </Form.Item>

            <div className="config-capability-section">
              <div className="config-capability-section-title">能力配置</div>
              <Select
                key={capabilityPickerKey}
                placeholder="添加 TTS、向量、记忆、图片、视频等能力"
                value={undefined}
                onChange={addCapability}
                options={CAPABILITIES
                  .filter((item) => !activeCapabilities.includes(item.key))
                  .map((item) => ({
                    value: item.key,
                    label: `${item.label} - ${item.description}`
                  }))}
                style={{ width: '100%' }}
              />
              {activeCapabilities.length === 0 && (
                <div className="config-capability-empty">
                  当前只保存基础大模型配置，需要 TTS、RAG、记忆或视觉生成时再添加对应能力。
                </div>
              )}
              {activeCapabilities.map(renderCapabilityField)}
            </div>
          </Form>
        </Modal>
      </Modal>
    </>
  );
};

export default LLMConfigModal;
