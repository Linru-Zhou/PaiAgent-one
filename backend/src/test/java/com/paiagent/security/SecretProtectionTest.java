package com.paiagent.security;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.SchemaMigrationRunner;
import com.paiagent.controller.LLMConfigController;
import com.paiagent.dto.McpToolConfigRequest;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.entity.Workflow;
import com.paiagent.service.LLMGlobalConfigService;
import com.paiagent.service.McpToolConfigService;
import com.paiagent.service.WorkflowService;
import com.paiagent.support.PaiAgentSpringTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API Key 保护：落库是密文、接口只返回脱敏值、脱敏值回传保存不会破坏真实密钥、引擎读到的仍是明文。
 * 全部断言都直接读原始列（JdbcTemplate 绕过 TypeHandler），验证"库里到底存了什么"。
 */
@PaiAgentSpringTest
class SecretProtectionTest {

    private static final String KEY_A = "sk-test-AAAAAAAAAAAAAAAAAAAAAAAA1111";
    private static final String KEY_B = "sk-test-BBBBBBBBBBBBBBBBBBBBBBBB2222";

    @Autowired
    private SecretCodec codec;
    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private LLMGlobalConfigService configService;
    @Autowired
    private LLMConfigController configController;
    @Autowired
    private McpToolConfigService mcpService;
    @Autowired
    private SchemaMigrationRunner migrationRunner;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> workflowIds = new ArrayList<>();
    private final List<Long> configIds = new ArrayList<>();
    private final List<Long> mcpIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        mcpIds.forEach(id -> jdbc.update("DELETE FROM mcp_tool_config WHERE id = ?", id));
        workflowIds.forEach(id -> jdbc.update("DELETE FROM workflow WHERE id = ?", id));
        configIds.forEach(id -> jdbc.update("DELETE FROM llm_global_config WHERE id = ?", id));
    }

    // ------------------------------------------------------------------ 编解码本身

    @Test
    void roundTripUsesRandomIvAndRejectsTampering() {
        String c1 = codec.encrypt(KEY_A);
        String c2 = codec.encrypt(KEY_A);

        assertTrue(c1.startsWith(SecretCodec.PREFIX));
        assertFalse(c1.contains(KEY_A));
        assertNotEquals(c1, c2, "同一明文两次加密必须不同（随机 IV）");
        assertEquals(KEY_A, codec.decrypt(c1));
        assertEquals(KEY_A, codec.decrypt(c2));
        assertEquals(c1, codec.encrypt(c1), "已加密的值不能被二次加密");
        assertEquals(KEY_A, codec.decrypt(KEY_A), "无前缀的历史明文原样透传");

        String tampered = c1.substring(0, c1.length() - 4) + (c1.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertEquals("", codec.decrypt(tampered), "GCM 认证失败必须解不出来，而不是返回乱码");
    }

    @Test
    void maskAndRestoreRules() {
        String masked = SecretCodec.mask(KEY_A);
        assertEquals("sk-********1111", masked);
        assertTrue(SecretCodec.isMasked(masked));
        assertFalse(SecretCodec.isMasked(KEY_A));
        assertEquals("********", SecretCodec.mask("short"));

        assertEquals(KEY_A, SecretCodec.restoreIfMasked(masked, KEY_A));
        assertNull(SecretCodec.restoreIfMasked(masked, KEY_B), "脱敏串与现有密钥对不上时不能还原");
        assertNull(SecretCodec.restoreIfMasked(masked, null));
        assertEquals(KEY_B, SecretCodec.restoreIfMasked(KEY_B, KEY_A), "用户输入了新密钥就用新的");
    }

    @Test
    void flowDataWithoutSecretsIsLeftByteIdentical() {
        // 含 apiKey 字段但值为空：会被解析一遍，但没有任何值发生变化，必须原样返回而不是重新序列化
        String raw = "{ \"nodes\" : [ {\"id\":\"n1\", \"data\":{\"apiKey\":\"\", \"temperature\":0.70, \"prompt\":\"你好\"}} ],\n \"edges\":[] }";
        assertEquals(raw, codec.encryptFlowData(raw));
        assertEquals(raw, codec.decryptFlowData(raw));
        assertEquals(raw, codec.maskFlowData(raw));
    }

    // ------------------------------------------------------------------ 工作流

    @Test
    void workflowKeyIsEncryptedAtRestMaskedInResponseAndPlainForEngine() {
        WorkflowResponse created = workflowService.createWorkflow(request(flow("n1", KEY_A)));
        workflowIds.add(created.getId());

        String raw = rawFlowData(created.getId());
        assertFalse(raw.contains(KEY_A), "库里不能出现明文密钥");
        assertTrue(raw.contains(SecretCodec.PREFIX));

        assertFalse(created.getFlowData().contains(KEY_A), "接口响应不能带明文");
        assertEquals("sk-********1111", apiKeyOf(created.getFlowData(), "n1"));
        assertEquals("sk-********1111",
                apiKeyOf(workflowService.getWorkflowById(created.getId()).getFlowData(), "n1"));
        assertTrue(workflowService.listWorkflows().stream()
                .filter(w -> w.getId().equals(created.getId()))
                .noneMatch(w -> w.getFlowData().contains(KEY_A)));

        Workflow forEngine = workflowService.getById(created.getId());
        assertEquals(KEY_A, apiKeyOf(forEngine.getFlowData(), "n1"), "引擎读到的必须是可用的明文");
    }

    @Test
    void savingBackTheMaskedValueKeepsTheRealKey() {
        WorkflowResponse created = workflowService.createWorkflow(request(flow("n1", KEY_A)));
        workflowIds.add(created.getId());

        // 前端：GET 拿到脱敏值 → 用户只改了别的 → 原样 PUT 回来
        WorkflowResponse loaded = workflowService.getWorkflowById(created.getId());
        workflowService.updateWorkflow(created.getId(), request(loaded.getFlowData()));

        assertEquals(KEY_A, apiKeyOf(workflowService.getById(created.getId()).getFlowData(), "n1"));
        assertFalse(rawFlowData(created.getId()).contains("********"), "脱敏串绝不能被当成密钥写进库");

        // 用户这次真的换了密钥
        workflowService.updateWorkflow(created.getId(), request(flow("n1", KEY_B)));
        assertEquals(KEY_B, apiKeyOf(workflowService.getById(created.getId()).getFlowData(), "n1"));
    }

    @Test
    void copiedNodeWithMaskedKeyIsRestoredByMaskAndUnrestorableIsCleared() {
        WorkflowResponse created = workflowService.createWorkflow(request(flow("n1", KEY_A)));
        workflowIds.add(created.getId());

        // 画布里复制了节点：新节点 id 不同，但脱敏 key 是复制来的
        String masked = SecretCodec.mask(KEY_A);
        String withCopy = "{\"nodes\":[{\"id\":\"n1\",\"data\":{\"apiKey\":\"" + masked + "\"}},"
                + "{\"id\":\"n2\",\"data\":{\"apiKey\":\"" + masked + "\"}}],\"edges\":[]}";
        workflowService.updateWorkflow(created.getId(), request(withCopy));
        String plain = workflowService.getById(created.getId()).getFlowData();
        assertEquals(KEY_A, apiKeyOf(plain, "n1"));
        assertEquals(KEY_A, apiKeyOf(plain, "n2"));

        // 另存为新工作流时带着脱敏值：没有可还原的真实值 → 清空，让用户重填，而不是存一个必然 401 的假密钥
        WorkflowResponse clone = workflowService.createWorkflow(request(flow("n1", masked)));
        workflowIds.add(clone.getId());
        assertEquals("", apiKeyOf(workflowService.getById(clone.getId()).getFlowData(), "n1"));
    }

    // ------------------------------------------------------------------ 全局模型配置

    @Test
    void globalConfigKeyIsEncryptedAtRestMaskedInApiAndPlainForExecutors() {
        LLMGlobalConfig saved = configService.saveConfig(newConfig("secret-test-" + UUID.randomUUID(), KEY_A));
        configIds.add(saved.getId());

        String raw = jdbc.queryForObject("SELECT api_key FROM llm_global_config WHERE id = ?", String.class, saved.getId());
        assertTrue(raw.startsWith(SecretCodec.PREFIX));
        assertFalse(raw.contains(KEY_A));

        assertEquals(KEY_A, configService.getById(saved.getId()).getApiKey(), "执行器通过 service 读到的是明文");

        LLMGlobalConfig viaApi = configController.getById(saved.getId()).getData();
        assertEquals("sk-********1111", viaApi.getApiKey());
        assertTrue(configController.listAll().getData().stream()
                .filter(c -> c.getId().equals(saved.getId()))
                .allMatch(c -> "sk-********1111".equals(c.getApiKey())));
        assertEquals("sk-********1111", configController.saveConfig(newConfigWithId(saved, saved.getApiKey())).getData().getApiKey());
    }

    @Test
    void editingGlobalConfigWithMaskedKeyKeepsRealKeyAndNewConfigWithMaskedKeyIsRejected() {
        LLMGlobalConfig saved = configService.saveConfig(newConfig("secret-test-" + UUID.randomUUID(), KEY_A));
        configIds.add(saved.getId());

        // 前端编辑：表单里是脱敏值，用户只改了模型名
        LLMGlobalConfig edit = newConfigWithId(saved, SecretCodec.mask(KEY_A));
        edit.setModel("changed-model");
        configService.saveConfig(edit);

        LLMGlobalConfig after = configService.getById(saved.getId());
        assertEquals(KEY_A, after.getApiKey());
        assertEquals("changed-model", after.getModel());

        LLMGlobalConfig fresh = newConfig("secret-test-" + UUID.randomUUID(), SecretCodec.mask(KEY_A));
        assertThrows(IllegalArgumentException.class, () -> configService.saveConfig(fresh));
    }

    // ------------------------------------------------------------------ MCP 工具的环境变量

    @Test
    void mcpSecretEnvIsEncryptedAtRestButOtherVarsAndReadersStayPlain() {
        Long id = createMcp(Map.of("MY_API_KEY", KEY_A, "LOG_LEVEL", "info"));

        String raw = jdbc.queryForObject("SELECT CAST(env AS CHAR) FROM mcp_tool_config WHERE id = ?", String.class, id);
        assertFalse(raw.contains(KEY_A), "密钥类变量在库里必须是密文");
        assertTrue(raw.contains(SecretCodec.PREFIX));
        assertTrue(raw.contains("\"info\""), "非密钥变量保持明文，方便排查");

        assertEquals(KEY_A, mcpService.parseEnv(mcpService.requireConfig(id)).get("MY_API_KEY"), "运行 MCP 子进程时拿到的是明文");
        assertEquals("info", mcpService.parseEnv(mcpService.requireConfig(id)).get("LOG_LEVEL"));
        Object shown = mcpService.listConfigs().stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow().get("env");
        assertEquals("******", ((Map<?, ?>) shown).get("MY_API_KEY"), "接口里只显示占位符");
    }

    @Test
    void mcpUpdateWithPlaceholderKeepsRealSecretAndPlaceholderWithoutOriginalIsRejected() {
        Long id = createMcp(Map.of("MY_API_KEY", KEY_A, "LOG_LEVEL", "info"));

        // 回传接口里看到的占位符，只改了日志级别：真实密钥不能被 ****** 覆盖（这是修复前就存在的缺陷）
        McpToolConfigRequest edit = mcpRequest(Map.of("MY_API_KEY", "******", "LOG_LEVEL", "debug"));
        mcpService.updateConfig(id, edit);
        Map<String, String> env = mcpService.parseEnv(mcpService.requireConfig(id));
        assertEquals(KEY_A, env.get("MY_API_KEY"));
        assertEquals("debug", env.get("LOG_LEVEL"));

        McpToolConfigRequest ghost = mcpRequest(Map.of("OTHER_TOKEN", "******"));
        assertThrows(IllegalArgumentException.class, () -> mcpService.updateConfig(id, ghost));
        assertThrows(IllegalArgumentException.class, () -> mcpService.createConfig(ghost));
    }

    private Long createMcp(Map<String, String> env) {
        Object id = mcpService.createConfig(mcpRequest(env)).get("id");
        mcpIds.add(((Number) id).longValue());
        return ((Number) id).longValue();
    }

    private static McpToolConfigRequest mcpRequest(Map<String, String> env) {
        McpToolConfigRequest request = new McpToolConfigRequest();
        request.setName("secret-test-" + UUID.randomUUID());
        request.setToolName("t");
        request.setCommand("echo");
        request.setEnv(env);
        return request;
    }

    // ------------------------------------------------------------------ 历史明文迁移

    @Test
    void startupMigrationEncryptsLegacyPlaintextOnceAndKeepsUpdatedAt() {
        jdbc.update("INSERT INTO workflow (name, flow_data, engine_type) VALUES (?, ?, 'dag')",
                "legacy-plain-" + UUID.randomUUID(), flow("n1", KEY_A));
        Long wid = jdbc.queryForObject("SELECT MAX(id) FROM workflow", Long.class);
        workflowIds.add(wid);

        jdbc.update("INSERT INTO llm_global_config (provider, config_name, api_url, api_key, model) VALUES ('openai', ?, 'http://x', ?, 'm')",
                "legacy-plain-" + UUID.randomUUID(), KEY_A);
        Long cid = jdbc.queryForObject("SELECT MAX(id) FROM llm_global_config", Long.class);
        configIds.add(cid);

        jdbc.update("INSERT INTO mcp_tool_config (name, tool_name, command, env) VALUES (?, 't', 'echo', ?)",
                "legacy-plain-" + UUID.randomUUID(), "{\"MY_API_KEY\": \"" + KEY_A + "\", \"LOG_LEVEL\": \"info\"}");
        Long mid = jdbc.queryForObject("SELECT MAX(id) FROM mcp_tool_config", Long.class);
        mcpIds.add(mid);

        Object updatedBefore = jdbc.queryForObject("SELECT updated_at FROM workflow WHERE id = ?", Object.class, wid);
        assertTrue(rawFlowData(wid).contains(KEY_A), "前置条件：迁移前是明文");

        migrationRunner.run(null);

        assertFalse(rawFlowData(wid).contains(KEY_A));
        assertTrue(rawFlowData(wid).contains(SecretCodec.PREFIX));
        assertFalse(jdbc.queryForObject("SELECT api_key FROM llm_global_config WHERE id = ?", String.class, cid).contains(KEY_A));
        assertEquals(updatedBefore, jdbc.queryForObject("SELECT updated_at FROM workflow WHERE id = ?", Object.class, wid),
                "迁移不能改 updated_at，否则列表排序会被打乱");
        assertEquals(KEY_A, apiKeyOf(workflowService.getById(wid).getFlowData(), "n1"));
        assertEquals(KEY_A, configService.getById(cid).getApiKey());
        assertFalse(jdbc.queryForObject("SELECT CAST(env AS CHAR) FROM mcp_tool_config WHERE id = ?", String.class, mid).contains(KEY_A));
        assertEquals(KEY_A, mcpService.parseEnv(mcpService.requireConfig(mid)).get("MY_API_KEY"));

        String afterFirst = rawFlowData(wid);
        migrationRunner.run(null);
        assertEquals(afterFirst, rawFlowData(wid), "重复启动是幂等的，不会再次加密");
    }

    // ------------------------------------------------------------------ helpers

    private String rawFlowData(Long id) {
        return jdbc.queryForObject("SELECT CAST(flow_data AS CHAR) FROM workflow WHERE id = ?", String.class, id);
    }

    private static String flow(String nodeId, String apiKey) {
        return "{\"nodes\":[{\"id\":\"" + nodeId + "\",\"type\":\"openai\",\"data\":{\"apiKey\":\"" + apiKey
                + "\",\"model\":\"m\"}}],\"edges\":[]}";
    }

    private static String apiKeyOf(String flowData, String nodeId) {
        for (Object n : JSON.parseObject(flowData).getJSONArray("nodes")) {
            JSONObject node = (JSONObject) n;
            if (nodeId.equals(node.getString("id"))) {
                return node.getJSONObject("data").getString("apiKey");
            }
        }
        throw new AssertionError("node not found: " + nodeId);
    }

    private static WorkflowRequest request(String flowData) {
        WorkflowRequest request = new WorkflowRequest();
        request.setName("secret-test-" + UUID.randomUUID());
        request.setFlowData(flowData);
        request.setEngineType("dag");
        return request;
    }

    private static LLMGlobalConfig newConfig(String name, String apiKey) {
        LLMGlobalConfig config = new LLMGlobalConfig();
        config.setProvider("openai");
        config.setConfigName(name);
        config.setApiUrl("http://localhost:1/v1");
        config.setApiKey(apiKey);
        config.setModel("m");
        return config;
    }

    private static LLMGlobalConfig newConfigWithId(LLMGlobalConfig base, String apiKey) {
        LLMGlobalConfig config = newConfig(base.getConfigName(), apiKey);
        config.setId(base.getId());
        return config;
    }
}
