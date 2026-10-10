package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.paiagent.config.MinioConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次执行结束后，把结果写成 executions/exec-&lt;编号&gt;/result.json：最终输出、每个节点的状态 / 耗时 / 输出，
 * 以及"产物链接 → 本地文件"的对应关系，方便在文件夹里直接找到音频、图片、视频。
 * 不写入节点输入（体积大且是上游输出的重复），也不会包含 API Key。
 */
@Slf4j
@Component
public class ExecutionResultExporter {

    @Autowired(required = false)
    private OutputFolder outputFolder;

    @Autowired(required = false)
    private MinioConfig minioConfig;

    /** @return 写入的文件路径；关闭或失败返回 null */
    public Path export(Long executionId, Long flowId, String status, String inputData, String outputData,
                       String nodeResultsJson, String errorMessage, int durationMs) {
        if (outputFolder == null || !outputFolder.enabled() || executionId == null) {
            return null;
        }
        try {
            Path dir = outputFolder.executionDir(executionId);
            if (dir == null) {
                return null;
            }
            Files.createDirectories(dir);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("executionId", executionId);
            result.put("workflowId", flowId);
            result.put("status", status);
            result.put("durationMs", durationMs);
            result.put("finishedAt", LocalDateTime.now().toString());
            result.put("input", inputData);
            result.put("errorMessage", errorMessage);
            result.put("output", parseIfJson(outputData));

            List<Object> nodes = new ArrayList<>();
            List<Map<String, String>> artifacts = new ArrayList<>();
            if (nodeResultsJson != null && !nodeResultsJson.isBlank()) {
                JSONArray array = JSON.parseArray(nodeResultsJson);
                for (Object item : array) {
                    JSONObject node = (JSONObject) item;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("nodeId", node.get("nodeId"));
                    entry.put("nodeName", node.get("nodeName"));
                    entry.put("status", node.get("status"));
                    entry.put("durationMs", node.get("duration"));
                    entry.put("error", node.get("error"));
                    Object output = parseIfJson(node.get("output"));
                    entry.put("output", output);
                    collectArtifacts(output, artifacts);
                    nodes.add(entry);
                }
            }
            result.put("nodes", nodes);
            result.put("artifacts", artifacts);

            Path file = dir.resolve("result.json");
            Files.writeString(file, JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            log.warn("写出执行结果失败 #{}: {}", executionId, e.getMessage());
            return null;
        }
    }

    /** 输出里凡是 MinIO 公共地址开头的字符串，都对应到本地文件夹里的同名文件。 */
    private void collectArtifacts(Object value, List<Map<String, String>> artifacts) {
        if (minioConfig == null || minioConfig.getPublicUrl() == null || minioConfig.getBucketName() == null) {
            return;
        }
        String prefix = minioConfig.getPublicUrl() + "/" + minioConfig.getBucketName() + "/";
        collect(value, prefix, artifacts);
    }

    private void collect(Object value, String prefix, List<Map<String, String>> artifacts) {
        if (value instanceof String text) {
            if (text.startsWith(prefix)) {
                Path local = outputFolder.pathFor(text.substring(prefix.length()));
                boolean known = artifacts.stream().anyMatch(a -> a.get("url").equals(text));
                if (!known) {
                    Map<String, String> artifact = new LinkedHashMap<>();
                    artifact.put("url", text);
                    artifact.put("localPath", local != null && Files.exists(local) ? local.toString() : null);
                    artifacts.add(artifact);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, prefix, artifacts));
        } else if (value instanceof Iterable<?> items) {
            items.forEach(v -> collect(v, prefix, artifacts));
        }
    }

    private static Object parseIfJson(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return value;
        }
        try {
            return JSON.parse(trimmed);
        } catch (Exception e) {
            return value;
        }
    }
}
