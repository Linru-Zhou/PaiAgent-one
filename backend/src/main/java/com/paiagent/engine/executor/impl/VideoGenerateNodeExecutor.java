package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class VideoGenerateNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private static final int MAX_POLLS = 60;
    private static final long POLL_INTERVAL_MS = 5_000;

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final MinioService minioService;

    private long pollIntervalMs = POLL_INTERVAL_MS;

    public VideoGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                                     VolcengineAgentPlanClient agentPlanClient,
                                     MinioService minioService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.minioService = minioService;
    }

    /** 仅供单测缩短轮询间隔，生产代码保持默认 5s。 */
    void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        String prompt = configuredTextInput(node, input, "prompt");
        if (prompt == null) {
            prompt = textValue(node, input, "prompt", "input");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("视频生成节点缺少 prompt");
        }
        String referenceImageUrl = configuredTextInput(node, input, "referenceImageUrl");
        if (referenceImageUrl == null) {
            referenceImageUrl = stringData(node, "referenceImageUrl", null);
        }

        ResolvedAgentPlanConfig config = configResolver.resolve(node, "video");
        configResolver.validateApiConfig(config, "视频生成");

        emit(node, progressCallback, "视频任务提交中", Map.of("model", config.model()));
        String taskId = agentPlanClient.createVideoTask(
                config,
                prompt,
                referenceImageUrl,
                intData(node, "duration", 5),
                stringData(node, "resolution", null),
                stringData(node, "ratio", "adaptive"),
                stringData(node, "cameraMotion", null)
        );
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("视频生成接口未返回 taskId");
        }

        Map<String, Object> task = Map.of("status", "running");
        boolean succeeded = false;
        for (int i = 0; i < MAX_POLLS; i++) {
            emit(node, progressCallback, "视频生成中", Map.of("taskId", taskId, "poll", i + 1));
            Thread.sleep(pollIntervalMs);
            task = agentPlanClient.getVideoTask(config, taskId);
            String status = String.valueOf(task.getOrDefault("status", ""));
            if (isSuccess(status)) {
                succeeded = true;
                break;
            }
            if (isFailed(status)) {
                throw new IllegalStateException("视频生成失败: " + task);
            }
        }

        String videoUrl = asText(task.get("videoUrl"));
        String coverUrl = asText(task.get("coverUrl"));
        if (!succeeded || !StringUtils.hasText(videoUrl)) {
            throw new IllegalStateException("视频生成超时或未返回 videoUrl: " + task);
        }

        emit(node, progressCallback, "视频转存中", Map.of("taskId", taskId));
        String persistedVideoUrl = persistMedia(videoUrl, "videos/generated-" + taskId + ".mp4", "video/mp4");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("status", "succeeded");
        output.put("videoUrl", persistedVideoUrl);
        output.put("coverUrl", coverUrl);
        output.put("model", config.model());
        output.put("metadata", task.get("raw"));
        output.put("output", persistedVideoUrl);
        applyOutputParams(node, output);
        return output;
    }

    private boolean isSuccess(String status) {
        String normalized = status.toLowerCase();
        return normalized.contains("success") || normalized.contains("succeed") || normalized.contains("completed");
    }

    private boolean isFailed(String status) {
        String normalized = status.toLowerCase();
        return normalized.contains("fail") || normalized.contains("error") || normalized.contains("cancel")
                || normalized.contains("expire");
    }

    private String persistMedia(String sourceUrl, String objectName, String contentType) {
        try {
            return minioService.uploadFromUrl(sourceUrl, objectName, contentType);
        } catch (Exception e) {
            log.warn("视频转存 MinIO 失败，保留原始 URL: {}", e.getMessage());
            return sourceUrl;
        }
    }

    private void emit(WorkflowNode node, Consumer<ExecutionEvent> progressCallback, String message, Object data) {
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(node.getId(), node.getType(), message, data));
        }
    }

    @Override
    public String getSupportedNodeType() {
        return "video_generate";
    }
}
