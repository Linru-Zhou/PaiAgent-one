package com.paiagent.engine.executor.impl;

import com.paiagent.common.StructuredNodeError;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.VideoGenerationTask;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VideoTaskCheckpointService;
import com.paiagent.service.VolcengineAgentPlanClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class VideoGenerateNodeExecutor extends AbstractAgentPlanNodeExecutor {

    /** 续跑时用户对"上次未完成的视频任务"的选择（continue / regenerate），经 modifiedVariables 传入。 */
    public static final String ACTION_KEY = "videoTaskAction";
    private static final String ACTION_CONTINUE = "continue";
    private static final String ACTION_REGENERATE = "regenerate";

    private static final int MAX_POLLS = 60;
    private static final long POLL_INTERVAL_MS = 5_000;

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final MinioService minioService;
    private final VideoTaskCheckpointService checkpointService;

    private long pollIntervalMs = POLL_INTERVAL_MS;

    public VideoGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                                     VolcengineAgentPlanClient agentPlanClient,
                                     MinioService minioService,
                                     VideoTaskCheckpointService checkpointService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.minioService = minioService;
        this.checkpointService = checkpointService;
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

        int duration = intData(node, "duration", 5);
        String resolution = stringData(node, "resolution", null);
        String ratio = stringData(node, "ratio", "adaptive");
        String cameraMotion = stringData(node, "cameraMotion", null);

        String checkpointKey = idempotencyKey(input);
        String fingerprint = fingerprint(config.model(), prompt, referenceImageUrl, duration, resolution, ratio, cameraMotion);

        Map<String, Object> previousTask = new LinkedHashMap<>();
        String taskId = reusablePendingTaskId(
                node, input, progressCallback, config, checkpointKey, fingerprint, previousTask);
        if (taskId == null) {
            emit(node, progressCallback, "视频任务提交中", Map.of("model", config.model()));
            taskId = agentPlanClient.createVideoTask(
                    config, prompt, referenceImageUrl, duration, resolution, ratio, cameraMotion);
            if (!StringUtils.hasText(taskId)) {
                throw new IllegalStateException("视频生成接口未返回 taskId");
            }
            // 趁任务编号还在手上立刻落库：轮询超时或进程中断之后，续跑才找得回这个任务
            recordSubmitted(checkpointKey, taskId, fingerprint);
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
                markFailed(checkpointKey, taskId);
                throw new IllegalStateException("视频生成失败: " + task);
            }
        }

        String videoUrl = asText(task.get("videoUrl"));
        String coverUrl = asText(task.get("coverUrl"));
        if (!succeeded || !StringUtils.hasText(videoUrl)) {
            if (succeeded) {
                // 服务商说成功却没给地址，再等多久结果都一样，别让续跑去等它
                markFailed(checkpointKey, taskId);
            }
            // 超时则不动检查点：任务可能还在生成，留给续跑去找回
            throw new IllegalStateException("视频生成超时或未返回 videoUrl: " + task);
        }
        markSucceeded(checkpointKey, taskId);

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
        if (!previousTask.isEmpty()) {
            // 这次放弃了上一次的任务（重新生成 / 参数变了），把取消结果带出去，用户能看到旧任务是否真的停了
            output.put("previousTask", previousTask);
        }
        applyOutputParams(node, output);
        return output;
    }

    /**
     * 断点续跑时找回上一次提交、仍未结束的任务；返回 null 表示应当提交新任务。
     * 参数（prompt 等）变了就直接按新参数重新生成，不再询问；参数没变且用户还没选择时抛出
     * {@link DecisionRequiredException}，由前端让用户在"继续等待"和"重新生成"之间选，再带着答案续跑。
     */
    private String reusablePendingTaskId(WorkflowNode node, Map<String, Object> input,
                                         Consumer<ExecutionEvent> progressCallback, ResolvedAgentPlanConfig config,
                                         String checkpointKey, String fingerprint, Map<String, Object> previousTask) {
        VideoGenerationTask pending = findPending(checkpointKey);
        if (pending == null) {
            return null;
        }
        String pendingTaskId = pending.getTaskId();
        if (!fingerprint.equals(pending.getFingerprint())) {
            log.info("视频参数已变化，不复用上次未完成的任务 {}，按新参数重新生成", pendingTaskId);
            abandonPendingTask(node, progressCallback, config, checkpointKey, pendingTaskId, previousTask);
            return null;
        }

        String action = asText(input.get(ACTION_KEY));
        if (ACTION_REGENERATE.equalsIgnoreCase(action)) {
            abandonPendingTask(node, progressCallback, config, checkpointKey, pendingTaskId, previousTask);
            return null;
        }
        if (ACTION_CONTINUE.equalsIgnoreCase(action)) {
            emit(node, progressCallback, "继续等待上次未完成的视频任务", Map.of("taskId", pendingTaskId));
            return pendingTaskId;
        }

        // 还没有选择：先看一眼服务商那边的状态，已经确定失败的任务没必要再问
        String providerStatus = peekStatus(config, pendingTaskId);
        if (providerStatus != null && isFailed(providerStatus)) {
            markFailed(checkpointKey, pendingTaskId);
            return null;
        }
        throw new DecisionRequiredException(pendingTaskId, providerStatus);
    }

    /**
     * 放弃上一次的任务：不再要它的结果了，就尽力取消，免得它在服务商那边继续跑、继续计费
     * （不取消就等于"继续等待"）。取消失败（比如服务商不允许取消运行中的任务）不能挡住新任务，只如实记录。
     */
    private void abandonPendingTask(WorkflowNode node, Consumer<ExecutionEvent> progressCallback,
                                    ResolvedAgentPlanConfig config, String checkpointKey, String taskId,
                                    Map<String, Object> previousTask) {
        previousTask.put("taskId", taskId);
        try {
            agentPlanClient.cancelVideoTask(config, taskId);
            previousTask.put("cancelled", true);
            // 已经确定停了：万一新任务提交失败，下次续跑不用再为这个任务反问
            markFailed(checkpointKey, taskId);
            emit(node, progressCallback, "已取消上次未完成的视频任务", Map.of("taskId", taskId));
        } catch (Exception e) {
            log.warn("取消上次的视频任务 {} 失败，它可能仍在运行: {}", taskId, e.getMessage());
            previousTask.put("cancelled", false);
            previousTask.put("message", e.getMessage());
            emit(node, progressCallback, "上次的视频任务无法取消，可能仍在运行", Map.of("taskId", taskId));
        }
    }

    private String peekStatus(ResolvedAgentPlanConfig config, String taskId) {
        try {
            return asText(agentPlanClient.getVideoTask(config, taskId).get("status"));
        } catch (Exception e) {
            log.warn("查询上次视频任务 {} 的状态失败: {}", taskId, e.getMessage());
            return null;
        }
    }

    // 检查点只是为了续跑时少花钱，读写失败不能影响视频生成本身，退化成没有检查点的老行为

    private VideoGenerationTask findPending(String checkpointKey) {
        if (checkpointKey == null) {
            return null;
        }
        try {
            return checkpointService.findPending(checkpointKey).orElse(null);
        } catch (Exception e) {
            log.warn("读取视频任务检查点失败，按新任务处理: {}", e.getMessage());
            return null;
        }
    }

    private void recordSubmitted(String checkpointKey, String taskId, String fingerprint) {
        if (checkpointKey == null) {
            return;
        }
        try {
            checkpointService.recordSubmitted(checkpointKey, taskId, fingerprint);
        } catch (Exception e) {
            log.warn("保存视频任务检查点失败，续跑时将无法找回任务 {}: {}", taskId, e.getMessage());
        }
    }

    private void markSucceeded(String checkpointKey, String taskId) {
        if (checkpointKey == null) {
            return;
        }
        try {
            checkpointService.markSucceeded(checkpointKey, taskId);
        } catch (Exception e) {
            log.warn("更新视频任务检查点失败: {}", e.getMessage());
        }
    }

    private void markFailed(String checkpointKey, String taskId) {
        if (checkpointKey == null) {
            return;
        }
        try {
            checkpointService.markFailed(checkpointKey, taskId);
        } catch (Exception e) {
            log.warn("更新视频任务检查点失败: {}", e.getMessage());
        }
    }

    /** 请求指纹：模型和所有影响视频内容的参数，任何一项变了都不能再复用旧任务。 */
    static String fingerprint(String model, String prompt, String referenceImageUrl, int duration,
                              String resolution, String ratio, String cameraMotion) {
        // 字段之间用 \0 分隔，避免 ("ab", null) 与 ("a", "b") 拼出同一个串；null 与空串也分开编码
        StringBuilder canonical = new StringBuilder();
        for (Object field : new Object[]{model, prompt, referenceImageUrl, duration, resolution, ratio, cameraMotion}) {
            canonical.append(field == null ? "\u0001" : String.valueOf(field)).append('\u0000');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /**
     * 参数没变、上次的任务也还没结束，需要用户在"继续等待"和"重新生成"之间选一个。
     * NodeRunner 会把 {@link #errorCode()} 和 {@link #errorDetail()} 放进节点结果，
     * 前端据此弹出选择框，选完带着 {@link #ACTION_KEY} 再续跑一次。
     */
    public static class DecisionRequiredException extends IllegalStateException implements StructuredNodeError {

        public static final String ERROR_CODE = "VIDEO_TASK_PENDING";

        private final Map<String, Object> detail = new LinkedHashMap<>();

        DecisionRequiredException(String taskId, String providerStatus) {
            super("检测到上次提交的视频任务尚未取回结果（taskId: " + taskId + "，服务商状态: "
                    + (providerStatus == null ? "未知" : providerStatus) + "），请选择继续等待或重新生成");
            detail.put("taskId", taskId);
            detail.put("providerStatus", providerStatus);
        }

        @Override
        public String errorCode() {
            return ERROR_CODE;
        }

        @Override
        public Map<String, Object> errorDetail() {
            return detail;
        }
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
