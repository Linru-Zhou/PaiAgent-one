package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for Agent Plan capabilities. Endpoint details stay here instead of node executors.
 */
@Slf4j
@Service
public class VolcengineAgentPlanClient {

    public List<Double> createEmbedding(ResolvedAgentPlanConfig config, String input) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());
        request.put("input", input);

        JSONObject response = postJson(config, "/embeddings", request);
        JSONArray data = response.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        JSONArray embedding = data.getJSONObject(0).getJSONArray("embedding");
        if (embedding == null) {
            return List.of();
        }

        List<Double> vector = new ArrayList<>(embedding.size());
        for (Object value : embedding) {
            if (value instanceof Number number) {
                vector.add(number.doubleValue());
            }
        }
        return vector;
    }

    public Map<String, Object> generateImage(ResolvedAgentPlanConfig config, String prompt,
                                             String referenceImageUrl, String size, int count,
                                             String negativePrompt, String style) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());
        request.put("prompt", prompt);
        request.put("response_format", "url");
        request.put("output_format", "png");
        request.put("watermark", false);
        request.put("stream", false);
        if (StringUtils.hasText(size)) {
            request.put("size", size);
        }
        if (StringUtils.hasText(referenceImageUrl)) {
            request.put("image", referenceImageUrl);
        }

        JSONObject response = postJson(config, "/images/generations", request);
        List<String> urls = new ArrayList<>();
        JSONArray data = response.getJSONArray("data");
        if (data != null) {
            for (Object item : data) {
                if (item instanceof JSONObject object) {
                    String url = object.getString("url");
                    if (StringUtils.hasText(url)) {
                        urls.add(url);
                    }
                }
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("imageUrls", urls);
        output.put("metadata", response);
        return output;
    }

    public String createVideoTask(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl,
                                  int duration, String resolution, String ratio, String cameraMotion) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", config.model());

        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.add(textContent);
        if (StringUtils.hasText(referenceImageUrl)) {
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", referenceImageUrl);

            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            imageContent.put("image_url", imageUrl);
            content.add(imageContent);
        }
        request.put("content", content);

        request.put("generate_audio", true);
        request.put("duration", duration);
        if (StringUtils.hasText(resolution)) {
            request.put("resolution", resolution);
        }
        if (StringUtils.hasText(ratio)) {
            request.put("ratio", ratio);
        }
        request.put("watermark", false);

        JSONObject response = postJson(config, "/contents/generations/tasks", request);
        return firstText(response.getString("id"), response.getString("task_id"), response.getString("taskId"));
    }

    public Map<String, Object> getVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        JSONObject response = getJson(config, "/contents/generations/tasks/" + taskId);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", firstText(response.getString("status"), response.getString("task_status"), "running"));
        output.put("videoUrl", findString(response, "video_url", "videoUrl", "url"));
        output.put("coverUrl", findString(response, "last_frame_url", "cover_url", "coverUrl", "poster_url"));
        output.put("raw", response);
        return output;
    }

    /**
     * 取消（排队中）或删除（已结束）视频生成任务：DELETE /contents/generations/tasks/{id}。
     * 官方文档只写了成功返回 200，没有写哪些状态可以取消，所以正在运行的任务很可能被服务商拒绝，
     * 拒绝时抛出 IOException（带 HTTP 状态和响应体），由调用方决定怎么处理。
     */
    public void cancelVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        HttpURLConnection conn = openConnection(config, "/contents/generations/tasks/" + taskId, "DELETE", config.apiKey());
        readResponse(conn);
    }

    private JSONObject postJson(ResolvedAgentPlanConfig config, String path, JSONObject body) throws IOException {
        return postJson(config, path, body, config.apiKey());
    }

    private JSONObject postJson(ResolvedAgentPlanConfig config, String path, JSONObject body, String apiKey) throws IOException {
        HttpURLConnection conn = openConnection(config, path, "POST", apiKey);
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.setDoOutput(true);
        conn.getOutputStream().write(bytes);
        return readResponse(conn);
    }

    private JSONObject getJson(ResolvedAgentPlanConfig config, String path) throws IOException {
        HttpURLConnection conn = openConnection(config, path, "GET", config.apiKey());
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(ResolvedAgentPlanConfig config, String path, String method, String apiKey) throws IOException {
        URL url = URI.create(normalizeBaseUrl(config.apiUrl()) + normalizePath(path)).toURL();
        return openConnection(url, method, apiKey);
    }

    private HttpURLConnection openConnection(URL url, String method, String apiKey) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        return conn;
    }

    private JSONObject readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        byte[] bytes = (status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            throw new IOException("Agent Plan 调用失败, HTTP " + status + ": " + text);
        }
        return JSON.parseObject(text);
    }

    private String normalizeBaseUrl(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }

    private String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private String findString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        for (Object value : object.values()) {
            if (value instanceof JSONObject child) {
                String found = findString(child, keys);
                if (StringUtils.hasText(found)) {
                    return found;
                }
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

}
