package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.entity.McpToolConfig;
import com.paiagent.service.mcp.McpServerSpec;
import com.paiagent.service.mcp.McpSessionPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Agent Plan Harness 联网搜索的 MCP 调用。进程和会话由 {@link McpSessionPool} 管理并复用，
 * 这里只负责拼参数和整理返回结果。
 */
@Slf4j
@Service
public class SearchInfinityMcpClient {

    private static final String TOOL_NAME = "web_search";
    private static final Duration CALL_TIMEOUT = McpSessionPool.DEFAULT_CALL_TIMEOUT;

    private final McpSessionPool sessionPool;

    public SearchInfinityMcpClient(McpSessionPool sessionPool) {
        this.sessionPool = sessionPool;
    }

    /** 配置被改动或删除后，关掉旧配置对应的进程。 */
    public void evict(McpToolConfig mcpToolConfig) {
        if (mcpToolConfig != null) {
            sessionPool.evict(specOf(mcpToolConfig));
        }
    }

    /** 该配置对应的 MCP 服务提供的工具（tools/list）。 */
    public List<Map<String, Object>> listTools(McpToolConfig mcpToolConfig) throws IOException {
        try {
            return sessionPool.listTools(specOf(mcpToolConfig)).stream()
                    .<Map<String, Object>>map(tool -> new LinkedHashMap<>(tool))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取 MCP 工具列表被中断", e);
        } catch (TimeoutException e) {
            throw new IOException("读取 MCP 工具列表超时: " + e.getMessage(), e);
        }
    }

    private McpServerSpec specOf(McpToolConfig config) {
        return new McpServerSpec(resolveCommand(config.getCommand()), parseArgs(config.getArgs()), parseEnv(config.getEnv()));
    }

    public Map<String, Object> webSearch(McpToolConfig mcpToolConfig, String query, int count,
                                         String timeRange, Integer authLevel,
                                         String searchType) throws IOException {
        if (mcpToolConfig == null) {
            throw new IllegalArgumentException("联网搜索缺少 MCP 工具配置");
        }
        return doWebSearch(
                specOf(mcpToolConfig),
                query,
                count,
                timeRange,
                authLevel,
                searchType
        );
    }

    private Map<String, Object> doWebSearch(McpServerSpec spec, String query, int count, String timeRange,
                                            Integer authLevel, String searchType) throws IOException {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("联网搜索缺少 query");
        }

        try {
            JSONObject arguments = new JSONObject();
            arguments.put("Query", query);
            arguments.put("Count", Math.max(1, Math.min(count, "image".equalsIgnoreCase(searchType) ? 5 : 50)));
            arguments.put("SearchType", firstText(searchType, "web"));
            if (StringUtils.hasText(timeRange)) {
                arguments.put("TimeRange", timeRange);
            }
            if (authLevel != null) {
                arguments.put("AuthLevel", authLevel);
            }

            log.info("Harness 联网搜索 MCP 调用开始: tool={}, searchType={}, count={}, query={}",
                    TOOL_NAME, arguments.getString("SearchType"), arguments.getInteger("Count"), abbreviate(query, 120));
            JSONObject result = sessionPool.callTool(spec, TOOL_NAME, arguments, CALL_TIMEOUT);
            Map<String, Object> output = normalizeToolResult(result);
            log.info("Harness 联网搜索 MCP 调用完成: contentItems={}, isError={}",
                    ((List<?>) output.getOrDefault("content", List.of())).size(), output.get("isError"));
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("联网搜索 MCP 调用被中断", e);
        } catch (TimeoutException e) {
            throw new IOException("联网搜索 MCP 调用超时，请检查 uvx、网络或 mcp-server-askecho-search-infinity 是否可启动", e);
        }
    }

    private Map<String, Object> normalizeToolResult(JSONObject result) throws IOException {
        boolean isError = Boolean.TRUE.equals(result.getBoolean("isError"));
        JSONArray content = result.getJSONArray("content");
        Object structuredResult = extractStructuredResult(result.get("structuredContent"));
        List<Object> contentItems = content == null ? List.of() : new ArrayList<>(content);
        List<Object> parsedItems = new ArrayList<>();
        StringBuilder summary = new StringBuilder();

        if (structuredResult != null) {
            parsedItems.add(structuredResult);
        }
        if (content != null) {
            for (Object item : content) {
                if (!(item instanceof JSONObject object)) {
                    continue;
                }
                String text = object.getString("text");
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (summary.length() > 0) {
                    summary.append("\n");
                }
                summary.append(text);
                if (structuredResult == null) {
                    Object parsed = parseJsonIfPossible(text);
                    parsedItems.add(parsed != null ? parsed : Map.of("text", text));
                }
            }
        }

        if (isError) {
            throw new IOException("联网搜索 MCP 工具返回错误: " + abbreviate(summary.toString(), 500));
        }
        String businessError = findBusinessError(parsedItems);
        if (StringUtils.hasText(businessError)) {
            throw new IOException("联网搜索 API 返回错误: " + businessError);
        }

        List<Map<String, Object>> webResults = extractWebResults(parsedItems);
        String readableSummary = webResults.isEmpty() ? summary.toString() : summarizeWebResults(webResults);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", readableSummary);
        output.put("rawSummary", summary.toString());
        output.put("requestId", findFirstString(parsedItems, "RequestId"));
        output.put("resultCount", firstNumber(findFirstValue(parsedItems, "ResultCount"), webResults.size()));
        output.put("webResults", webResults);
        output.put("results", parsedItems);
        output.put("citations", webResults.isEmpty() ? extractCitations(parsedItems) : extractCitationsFromWebResults(webResults));
        output.put("content", contentItems);
        output.put("isError", false);
        output.put("raw", result);
        return output;
    }

    private Object extractStructuredResult(Object structuredContent) {
        if (structuredContent instanceof JSONObject object && object.containsKey("result")) {
            return object.get("result");
        }
        return structuredContent;
    }

    private Object parseJsonIfPossible(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        try {
            return JSON.parse(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseArgs(String rawArgs) {
        if (!StringUtils.hasText(rawArgs)) {
            return List.of();
        }
        JSONArray array = JSON.parseArray(rawArgs);
        List<String> args = new ArrayList<>();
        for (Object item : array) {
            args.add(String.valueOf(item));
        }
        return args;
    }

    private Map<String, String> parseEnv(String rawEnv) {
        if (!StringUtils.hasText(rawEnv)) {
            return Map.of();
        }
        JSONObject object = JSON.parseObject(rawEnv);
        Map<String, String> env = new LinkedHashMap<>();
        object.forEach((key, value) -> env.put(key, value == null ? "" : String.valueOf(value)));
        return env;
    }

    private List<Object> extractCitations(List<Object> parsedItems) {
        List<Object> citations = new ArrayList<>();
        for (Object item : parsedItems) {
            collectCitations(item, citations);
        }
        return citations;
    }

    private List<Map<String, Object>> extractWebResults(List<Object> parsedItems) {
        List<Map<String, Object>> webResults = new ArrayList<>();
        for (Object item : parsedItems) {
            collectWebResults(item, webResults);
        }
        return webResults;
    }

    private void collectWebResults(Object value, List<Map<String, Object>> webResults) {
        if (value instanceof JSONObject object) {
            JSONArray results = object.getJSONArray("WebResults");
            if (results != null) {
                for (Object item : results) {
                    if (item instanceof JSONObject result) {
                        webResults.add(toWebResult(result));
                    }
                }
            }
            for (Object child : object.values()) {
                collectWebResults(child, webResults);
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                collectWebResults(child, webResults);
            }
        } else if (value instanceof Map<?, ?> map) {
            Object results = firstMapValue(map, "WebResults");
            if (results instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> result) {
                        webResults.add(toWebResult(result));
                    }
                }
            }
            for (Object child : map.values()) {
                collectWebResults(child, webResults);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectWebResults(child, webResults);
            }
        }
    }

    private Map<String, Object> toWebResult(JSONObject result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", result.getString("Id"));
        output.put("sortId", result.getInteger("SortId"));
        output.put("title", result.getString("Title"));
        output.put("siteName", result.getString("SiteName"));
        output.put("url", result.getString("Url"));
        output.put("snippet", result.getString("Snippet"));
        output.put("summary", result.getString("Summary"));
        return output;
    }

    private Map<String, Object> toWebResult(Map<?, ?> result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", firstMapValue(result, "Id", "id"));
        output.put("sortId", firstMapValue(result, "SortId", "sortId"));
        output.put("title", firstMapValue(result, "Title", "title"));
        output.put("siteName", firstMapValue(result, "SiteName", "siteName"));
        output.put("url", firstMapValue(result, "Url", "url"));
        output.put("snippet", firstMapValue(result, "Snippet", "snippet"));
        output.put("summary", firstMapValue(result, "Summary", "summary"));
        return output;
    }

    private List<Object> extractCitationsFromWebResults(List<Map<String, Object>> webResults) {
        List<Object> citations = new ArrayList<>();
        for (Map<String, Object> result : webResults) {
            Object url = result.get("url");
            if (url instanceof String text && looksLikeUrl(text)) {
                citations.add(Map.of(
                        "url", text,
                        "title", Objects.toString(result.getOrDefault("title", text), text),
                        "siteName", Objects.toString(result.getOrDefault("siteName", ""), "")
                ));
            }
        }
        return citations;
    }

    private String summarizeWebResults(List<Map<String, Object>> webResults) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> result : webResults) {
            String title = Objects.toString(result.get("title"), "");
            String siteName = Objects.toString(result.get("siteName"), "");
            String snippet = firstText(
                    Objects.toString(result.get("summary"), null),
                    Objects.toString(result.get("snippet"), null)
            );
            builder.append(index++).append(". ").append(title);
            if (StringUtils.hasText(siteName)) {
                builder.append(" - ").append(siteName);
            }
            if (StringUtils.hasText(snippet)) {
                builder.append("\n").append(snippet);
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String findBusinessError(List<Object> parsedItems) {
        for (Object item : parsedItems) {
            String error = findBusinessError(item);
            if (StringUtils.hasText(error)) {
                return error;
            }
        }
        return null;
    }

    private String findBusinessError(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject responseMetadata = object.getJSONObject("ResponseMetadata");
            if (responseMetadata != null) {
                JSONObject error = responseMetadata.getJSONObject("Error");
                if (error != null && !error.isEmpty()) {
                    return formatError(error.getString("Code"), error.getString("Message"), error.toJSONString());
                }
            }
            for (Object child : object.values()) {
                String error = findBusinessError(child);
                if (StringUtils.hasText(error)) {
                    return error;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                String error = findBusinessError(child);
                if (StringUtils.hasText(error)) {
                    return error;
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            Object responseMetadata = firstMapValue(map, "ResponseMetadata");
            if (responseMetadata instanceof Map<?, ?> metadata) {
                Object error = firstMapValue(metadata, "Error");
                if (error instanceof Map<?, ?> errorMap && !errorMap.isEmpty()) {
                    return formatError(
                            Objects.toString(firstMapValue(errorMap, "Code"), null),
                            Objects.toString(firstMapValue(errorMap, "Message"), null),
                            errorMap.toString()
                    );
                }
            }
            for (Object child : map.values()) {
                String error = findBusinessError(child);
                if (StringUtils.hasText(error)) {
                    return error;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                String error = findBusinessError(child);
                if (StringUtils.hasText(error)) {
                    return error;
                }
            }
        }
        return null;
    }

    private String formatError(String code, String message, String fallback) {
        if (StringUtils.hasText(code) && StringUtils.hasText(message)) {
            return code + ": " + message;
        }
        return firstText(code, message, fallback);
    }

    @SuppressWarnings("unchecked")
    private void collectCitations(Object value, List<Object> citations) {
        if (value instanceof JSONObject object) {
            for (String key : List.of("url", "Url", "link", "Link", "site", "Site")) {
                String text = object.getString(key);
                if (StringUtils.hasText(text) && looksLikeUrl(text)) {
                    citations.add(Map.of("url", text, "title", firstText(object.getString("title"), object.getString("Title"), text)));
                    break;
                }
            }
            for (Object child : object.values()) {
                collectCitations(child, citations);
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                collectCitations(child, citations);
            }
        } else if (value instanceof Map<?, ?> map) {
            Object url = firstMapValue(map, "url", "Url", "link", "Link");
            if (url instanceof String text && looksLikeUrl(text)) {
                citations.add(Map.of("url", text, "title", Objects.toString(firstMapValue(map, "title", "Title"), text)));
            }
            for (Object child : ((Map<Object, Object>) map).values()) {
                collectCitations(child, citations);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectCitations(child, citations);
            }
        }
    }

    private Object firstMapValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String findFirstString(List<Object> values, String key) {
        Object value = findFirstValue(values, key);
        return value == null ? null : String.valueOf(value);
    }

    private Object findFirstValue(Object value, String key) {
        if (value instanceof JSONObject object) {
            if (object.containsKey(key)) {
                return object.get(key);
            }
            for (Object child : object.values()) {
                Object found = findFirstValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                Object found = findFirstValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            Object direct = firstMapValue(map, key);
            if (direct != null) {
                return direct;
            }
            for (Object child : map.values()) {
                Object found = findFirstValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                Object found = findFirstValue(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private int firstNumber(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String resolveUvxCommand() {
        String configured = System.getenv("MCP_UVX_PATH");
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        File userLocal = new File(System.getProperty("user.home"), ".local/bin/uvx");
        if (userLocal.isFile() && userLocal.canExecute()) {
            return userLocal.getAbsolutePath();
        }
        return "uvx";
    }

    private String resolveCommand(String command) {
        if (!StringUtils.hasText(command) || "uvx".equals(command.trim())) {
            return resolveUvxCommand();
        }
        return command.trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
