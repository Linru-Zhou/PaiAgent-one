package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.dto.AgentPlanWebSearchMcpRequest;
import com.paiagent.dto.McpToolConfigRequest;
import com.paiagent.entity.McpToolConfig;
import com.paiagent.mapper.McpToolConfigMapper;
import com.paiagent.security.SecretCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpToolConfigService extends ServiceImpl<McpToolConfigMapper, McpToolConfig> {

    /** 接口返回的密钥类环境变量统一显示为该占位值；前端原样回传时要还原成库里的真实值。 */
    static final String ENV_MASK = "******";

    public static final String AGENT_PLAN_WEB_SEARCH = "agent_plan_web_search";
    public static final String WEB_SEARCH_TOOL_NAME = "web_search";
    public static final String SEARCH_API_KEY_ENV = "ASK_ECHO_SEARCH_INFINITY_API_KEY";
    public static final String SEARCH_INFINITY_PACKAGE =
            "git+https://github.com/volcengine/mcp-server#subdirectory=server/mcp_server_askecho_search_infinity";
    public static final String SEARCH_INFINITY_COMMAND = "mcp-server-askecho-search-infinity";

    private final SearchInfinityMcpClient searchInfinityMcpClient;

    public McpToolConfigService(SearchInfinityMcpClient searchInfinityMcpClient) {
        this.searchInfinityMcpClient = searchInfinityMcpClient;
    }

    public List<Map<String, Object>> listConfigs() {
        return this.list(new LambdaQueryWrapper<McpToolConfig>()
                        .orderByDesc(McpToolConfig::getUpdatedAt))
                .stream()
                .map(this::toSafeMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createConfig(McpToolConfigRequest request) {
        McpToolConfig config = new McpToolConfig();
        applyRequest(config, request);
        config.setPreset(0);
        this.save(config);
        return toSafeMap(config);
    }

    @Transactional
    public Map<String, Object> createAgentPlanWebSearch(AgentPlanWebSearchMcpRequest request) {
        McpToolConfig config = new McpToolConfig();
        config.setName(firstText(request.getName(), "Agent Plan 联网搜索"));
        config.setDescription(firstText(request.getDescription(), "通过火山 Agent Plan Harness MCP 使用联网搜索额度"));
        config.setToolType(AGENT_PLAN_WEB_SEARCH);
        config.setToolName(WEB_SEARCH_TOOL_NAME);
        config.setTransport("stdio");
        config.setCommand(resolveDefaultUvxCommand());
        config.setArgs(JSON.toJSONString(List.of(
                "--from",
                SEARCH_INFINITY_PACKAGE,
                SEARCH_INFINITY_COMMAND
        )));
        config.setEnv(JSON.toJSONString(Map.of(SEARCH_API_KEY_ENV, request.getApiKey().trim())));
        config.setEnabled(1);
        config.setPreset(1);
        this.save(config);
        return toSafeMap(config);
    }

    @Transactional
    public Map<String, Object> updateConfig(Long id, McpToolConfigRequest request) {
        McpToolConfig config = requireConfig(id);
        // 命令、参数或环境变量可能变了：先关掉按旧配置启动的常驻进程，下次调用按新配置重新拉起
        searchInfinityMcpClient.evict(config);
        applyRequest(config, request);
        this.updateById(config);
        return toSafeMap(config);
    }

    @Transactional
    public void deleteConfig(Long id) {
        searchInfinityMcpClient.evict(this.getById(id));
        this.removeById(id);
    }

    public McpToolConfig requireConfig(Long id) {
        McpToolConfig config = this.getById(id);
        if (config == null) {
            throw new IllegalArgumentException("MCP 工具不存在");
        }
        return config;
    }

    public List<McpToolConfig> resolveEnabledConfigs(Object rawIds) {
        List<Long> ids = parseIds(rawIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return this.listByIds(ids).stream()
                .filter(config -> config.getEnabled() != null && config.getEnabled() == 1)
                .toList();
    }

    public McpToolConfig resolveFirstEnabledWebSearch(Object rawIds) {
        return resolveEnabledConfigs(rawIds).stream()
                .filter(config -> WEB_SEARCH_TOOL_NAME.equals(config.getToolName()))
                .findFirst()
                .orElse(null);
    }

    public Map<String, Object> testConfig(Long id, String query) throws Exception {
        McpToolConfig config = requireConfig(id);
        if (!WEB_SEARCH_TOOL_NAME.equals(config.getToolName())) {
            throw new IllegalArgumentException("当前只支持测试 web_search MCP 工具");
        }
        String searchQuery = StringUtils.hasText(query) ? query.trim() : "今天的科技新闻";
        return searchInfinityMcpClient.webSearch(config, searchQuery, 3, null, null, "web");
    }

    /** 向该 MCP 服务发 tools/list，返回它声明的工具（名称、描述、参数 schema）。 */
    public List<Map<String, Object>> listTools(Long id) throws Exception {
        return searchInfinityMcpClient.listTools(requireConfig(id));
    }

    public List<String> parseArgs(McpToolConfig config) {
        if (!StringUtils.hasText(config.getArgs())) {
            return List.of();
        }
        JSONArray array = JSON.parseArray(config.getArgs());
        List<String> args = new ArrayList<>();
        for (Object item : array) {
            args.add(String.valueOf(item));
        }
        return args;
    }

    public Map<String, String> parseEnv(McpToolConfig config) {
        if (!StringUtils.hasText(config.getEnv())) {
            return Map.of();
        }
        JSONObject object = JSON.parseObject(config.getEnv());
        Map<String, String> env = new LinkedHashMap<>();
        object.forEach((key, value) -> env.put(key, value == null ? "" : String.valueOf(value)));
        return env;
    }

    private void applyRequest(McpToolConfig config, McpToolConfigRequest request) {
        config.setName(requireText(request.getName(), "MCP 名称不能为空"));
        config.setDescription(trimToNull(request.getDescription()));
        config.setToolType(firstText(request.getToolType(), "custom"));
        config.setToolName(requireText(request.getToolName(), "工具名不能为空"));
        config.setTransport(firstText(request.getTransport(), "stdio"));
        config.setCommand(requireText(request.getCommand(), "启动命令不能为空"));
        config.setArgs(JSON.toJSONString(request.getArgs() == null ? List.of() : request.getArgs()));
        config.setEnv(JSON.toJSONString(resolveEnv(request.getEnv(), parseEnv(config))));
        config.setEnabled(request.getEnabled() == null || request.getEnabled() == 1 ? 1 : 0);
        if (config.getPreset() == null) {
            config.setPreset(0);
        }
    }

    /**
     * 回传的密钥类变量如果还是占位值 ******，说明用户没改它：换回库里的真实值；
     * 找不到可还原的真实值（新建，或原本就没有这个变量）则拒绝，绝不把 ****** 当成密钥存下来。
     */
    private Map<String, String> resolveEnv(Map<String, String> requested, Map<String, String> existing) {
        if (requested == null) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        requested.forEach((key, value) -> {
            if (isSecretKey(key) && ENV_MASK.equals(value)) {
                String current = existing.get(key);
                if (!StringUtils.hasText(current)) {
                    throw new IllegalArgumentException("环境变量 " + key + " 的密钥无效，请重新输入完整的值");
                }
                resolved.put(key, current);
            } else {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    private Map<String, Object> toSafeMap(McpToolConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", config.getId());
        output.put("name", config.getName());
        output.put("description", config.getDescription());
        output.put("toolType", config.getToolType());
        output.put("toolName", config.getToolName());
        output.put("transport", config.getTransport());
        output.put("command", config.getCommand());
        output.put("args", parseArgs(config));
        output.put("env", maskEnv(parseEnv(config)));
        output.put("enabled", config.getEnabled());
        output.put("preset", config.getPreset());
        output.put("createdAt", config.getCreatedAt());
        output.put("updatedAt", config.getUpdatedAt());
        return output;
    }

    private Map<String, String> maskEnv(Map<String, String> env) {
        Map<String, String> masked = new LinkedHashMap<>();
        env.forEach((key, value) -> masked.put(key, isSecretKey(key) ? ENV_MASK : value));
        return masked;
    }

    private boolean isSecretKey(String key) {
        return SecretCodec.isSecretEnvKey(key);
    }

    private List<Long> parseIds(Object rawIds) {
        if (rawIds == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (rawIds instanceof List<?> list) {
            for (Object value : list) {
                parseId(value, ids);
            }
        } else {
            for (String item : String.valueOf(rawIds).split(",")) {
                parseId(item, ids);
            }
        }
        return ids;
    }

    private void parseId(Object value, List<Long> ids) {
        if (value instanceof Number number) {
            ids.add(number.longValue());
            return;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            ids.add(Long.parseLong(text.trim()));
        }
    }

    private String resolveDefaultUvxCommand() {
        String configured = System.getenv("MCP_UVX_PATH");
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        return "uvx";
    }

    private String requireText(String value, String message) {
        String text = trimToNull(value);
        if (text == null) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
