package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.VideoGenerationTask;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VideoTaskCheckpointService;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The executor sleeps between polls, so every test sets the poll interval to 0. Without that a single
 * poll costs 5s and the timeout case costs 5 minutes; the class-level timeout makes losing that seam
 * fail loudly instead of silently slowing the suite down.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class VideoGenerateNodeExecutorTest {

    private static final String TASK_ID = "task-1";
    private static final String VIDEO_URL = "https://origin.example/video.mp4";
    private static final String PERSISTED_URL = "https://minio.example/videos/generated-task-1.mp4";
    /** Mirrors VideoGenerateNodeExecutor.MAX_POLLS. */
    private static final int MAX_POLLS = 60;
    private static final String KEY = "42:node-1";
    private static final String OLD_TASK = "old-task";
    private static final String NEW_TASK = "new-task";

    @Mock
    private AgentPlanConfigResolver configResolver;

    @Mock
    private VolcengineAgentPlanClient agentPlanClient;

    @Mock
    private MinioService minioService;

    @Mock
    private VideoTaskCheckpointService checkpointService;

    @Mock
    private Consumer<ExecutionEvent> progressCallback;

    private final ResolvedAgentPlanConfig config = new ResolvedAgentPlanConfig(
            1L, "volcengine", "https://ark.example/api/v3", "test-key", "test-video-model",
            null, null, "test-video-model", false);

    private VideoGenerateNodeExecutor executor;
    private WorkflowNode node;
    private Map<String, Object> nodeData;

    @BeforeEach
    void setUp() {
        executor = new VideoGenerateNodeExecutor(configResolver, agentPlanClient, minioService, checkpointService);
        executor.setPollIntervalMs(0);

        nodeData = new HashMap<>();
        node = new WorkflowNode();
        node.setId("node-1");
        node.setType("video_generate");
        node.setData(nodeData);
    }

    @Test
    void execute_PollsUntilSucceeded_PersistsVideoAndReturnsOutput() throws Exception {
        givenTaskCreated(TASK_ID);
        Map<String, Object> raw = Map.of("id", TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "running"))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL,
                        "coverUrl", "https://origin.example/cover.jpg", "raw", raw));
        when(minioService.uploadFromUrl(VIDEO_URL, "videos/generated-task-1.mp4", "video/mp4")).thenReturn(PERSISTED_URL);

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "A cat playing piano"), progressCallback);

        assertEquals(TASK_ID, result.get("taskId"));
        assertEquals("succeeded", result.get("status"));
        assertEquals(PERSISTED_URL, result.get("videoUrl"));
        assertEquals(PERSISTED_URL, result.get("output"));
        assertEquals("https://origin.example/cover.jpg", result.get("coverUrl"));
        assertEquals("test-video-model", result.get("model"));
        assertEquals(raw, result.get("metadata"));
        verify(configResolver).validateApiConfig(config, "视频生成");
        verify(agentPlanClient).createVideoTask(config, "A cat playing piano", null, 5, null, "adaptive", null);
        verify(agentPlanClient, times(2)).getVideoTask(config, TASK_ID);
    }

    @Test
    void execute_EmitsOneProgressEventPerStageAndPoll() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "running"))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);

        executor.execute(node, Map.of("prompt", "progress"), progressCallback);

        // submit, poll 1 (still running), poll 2 (succeeded), persist
        ArgumentCaptor<ExecutionEvent> events = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(progressCallback, times(4)).accept(events.capture());
        List<ExecutionEvent> emitted = events.getAllValues();
        assertEquals(List.of("视频任务提交中", "视频生成中", "视频生成中", "视频转存中"),
                emitted.stream().map(ExecutionEvent::getMessage).toList());
        assertEquals(Map.of("model", "test-video-model"), emitted.get(0).getData());
        assertEquals(Map.of("taskId", TASK_ID, "poll", 1), emitted.get(1).getData());
        assertEquals(Map.of("taskId", TASK_ID, "poll", 2), emitted.get(2).getData());
        assertEquals(Map.of("taskId", TASK_ID), emitted.get(3).getData());
        emitted.forEach(event -> {
            assertEquals("NODE_PROGRESS", event.getEventType());
            assertEquals("node-1", event.getNodeId());
            assertEquals("video_generate", event.getNodeName());
        });
    }

    @Test
    void execute_WithoutProgressCallback_StillSucceeds() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "no callback"));

        assertEquals(PERSISTED_URL, result.get("videoUrl"));
    }

    @Test
    void execute_ConfiguredPromptParam_TakesPrecedenceOverInput() throws Exception {
        nodeData.put("inputParams", List.of(Map.of("name", "prompt", "type", "input", "value", "configured prompt")));
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, Map.of("prompt", "from upstream"));

        verify(agentPlanClient).createVideoTask(config, "configured prompt", null, 5, null, "adaptive", null);
    }

    @Test
    void execute_PromptFallsBackToUpstreamInput() throws Exception {
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, Map.of("input", "upstream text"));

        verify(agentPlanClient).createVideoTask(config, "upstream text", null, 5, null, "adaptive", null);
    }

    @Test
    void execute_NodeSettings_AreForwardedToClient() throws Exception {
        nodeData.put("duration", 8);
        nodeData.put("resolution", "1080p");
        nodeData.put("ratio", "16:9");
        nodeData.put("cameraMotion", "zoom_in");
        nodeData.put("referenceImageUrl", "https://img.example/ref.jpg");
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, Map.of("prompt", "settings"));

        verify(agentPlanClient).createVideoTask(
                config, "settings", "https://img.example/ref.jpg", 8, "1080p", "16:9", "zoom_in");
    }

    @Test
    void execute_ConfiguredReferenceImageParam_TakesPrecedenceOverNodeData() throws Exception {
        nodeData.put("referenceImageUrl", "https://img.example/from-data.jpg");
        nodeData.put("inputParams", List.of(
                Map.of("name", "referenceImageUrl", "type", "input", "value", "https://img.example/from-param.jpg")));
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, Map.of("prompt", "reference"));

        verify(agentPlanClient).createVideoTask(
                config, "reference", "https://img.example/from-param.jpg", 5, null, "adaptive", null);
    }

    @Test
    void execute_MissingPrompt_ThrowsBeforeCallingAnyService() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(node, Map.of(), progressCallback));

        assertEquals("视频生成节点缺少 prompt", exception.getMessage());
        verifyNoInteractions(configResolver, agentPlanClient, minioService, progressCallback);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void execute_BlankTaskId_ThrowsAndNeverPolls(String taskId) throws Exception {
        givenTaskCreated(taskId);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "no id"), progressCallback));

        assertEquals("视频生成接口未返回 taskId", exception.getMessage());
        verify(agentPlanClient, never()).getVideoTask(any(), anyString());
        verifyNoInteractions(minioService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "FAILED", "error", "cancelled", "expired"})
    void execute_TerminalFailureStatus_ThrowsWithoutFurtherPolling(String status) throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", status, "error", "internal error"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "fails"), progressCallback));

        assertTrue(exception.getMessage().contains("视频生成失败"));
        verify(agentPlanClient, times(1)).getVideoTask(config, TASK_ID);
        verifyNoInteractions(minioService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "succeeded", "Succeed", "completed"})
    void execute_SuccessStatusVariants_StopPolling(String status) throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", status, "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "variants"));

        assertEquals("succeeded", result.get("status"));
        verify(agentPlanClient, times(1)).getVideoTask(config, TASK_ID);
    }

    @Test
    void execute_SucceededWithoutVideoUrl_Throws() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", "succeeded"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "no url"), progressCallback));

        assertTrue(exception.getMessage().contains("视频生成超时或未返回 videoUrl"));
        verifyNoInteractions(minioService);
    }

    @Test
    void execute_StillRunningAfterMaxPolls_TimesOut() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", "running"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "never finishes"), progressCallback));

        assertTrue(exception.getMessage().contains("视频生成超时或未返回 videoUrl"));
        verify(agentPlanClient, times(MAX_POLLS)).getVideoTask(config, TASK_ID);
        // submit + one event per poll, and nothing is persisted
        verify(progressCallback, times(MAX_POLLS + 1)).accept(any(ExecutionEvent.class));
        verifyNoInteractions(minioService);
    }

    @Test
    void execute_StillRunningAfterMaxPollsEvenWithUrl_TimesOutInsteadOfPersisting() throws Exception {
        // VolcengineAgentPlanClient.findString falls back to any nested "url", so a task that never
        // reached a success status can still carry a non-blank videoUrl (e.g. an echoed reference image).
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "running", "videoUrl", "https://origin.example/not-a-video.png"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "running with url"), progressCallback));

        assertTrue(exception.getMessage().contains("视频生成超时或未返回 videoUrl"));
        verify(agentPlanClient, times(MAX_POLLS)).getVideoTask(config, TASK_ID);
        verifyNoInteractions(minioService);
    }

    @Test
    void execute_MinioUploadFails_KeepsOriginalUrl() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("MinIO connection refused"));

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "minio down"), progressCallback);

        assertEquals("succeeded", result.get("status"));
        assertEquals(VIDEO_URL, result.get("videoUrl"));
        assertEquals(VIDEO_URL, result.get("output"));
    }

    @Test
    void execute_OutputParams_AreMappedFromResult() throws Exception {
        nodeData.put("outputParams", List.of(Map.of("name", "clip", "value", "videoUrl")));
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "output params"));

        assertEquals(PERSISTED_URL, result.get("clip"));
    }

    @Test
    void execute_WithoutIdempotencyKey_NeverTouchesCheckpointStore() throws Exception {
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, Map.of("prompt", "no key"));

        verifyNoInteractions(checkpointService);
    }

    @Test
    void execute_NewTask_RecordsTaskIdRightAfterSubmitBeforePolling() throws Exception {
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        executor.execute(node, inputWithKey("record me"));

        InOrder order = inOrder(agentPlanClient, checkpointService);
        order.verify(agentPlanClient).createVideoTask(config, "record me", null, 5, null, "adaptive", null);
        order.verify(checkpointService).recordSubmitted(eq(KEY), eq(TASK_ID), anyString());
        order.verify(agentPlanClient).getVideoTask(config, TASK_ID);
        order.verify(checkpointService).markSucceeded(KEY, TASK_ID);
    }

    @Test
    void execute_PendingTaskWithSameParamsAndNoChoice_AsksInsteadOfSubmitting() throws Exception {
        givenPendingTask(OLD_TASK, "same prompt");
        when(agentPlanClient.getVideoTask(config, OLD_TASK)).thenReturn(Map.of("status", "running"));

        VideoGenerateNodeExecutor.DecisionRequiredException exception = assertThrows(
                VideoGenerateNodeExecutor.DecisionRequiredException.class,
                () -> executor.execute(node, inputWithKey("same prompt"), progressCallback));

        assertEquals("VIDEO_TASK_PENDING", exception.errorCode());
        assertEquals(OLD_TASK, exception.errorDetail().get("taskId"));
        assertEquals("running", exception.errorDetail().get("providerStatus"));
        verifyNothingSubmitted();
        verifyNoInteractions(minioService);
    }

    @Test
    void execute_PendingTaskWithSameParamsAndUnknownProviderStatus_StillAsks() throws Exception {
        givenPendingTask(OLD_TASK, "same prompt");
        when(agentPlanClient.getVideoTask(config, OLD_TASK)).thenThrow(new IOException("connection reset"));

        VideoGenerateNodeExecutor.DecisionRequiredException exception = assertThrows(
                VideoGenerateNodeExecutor.DecisionRequiredException.class,
                () -> executor.execute(node, inputWithKey("same prompt")));

        assertTrue(exception.getMessage().contains("未知"));
        assertEquals(OLD_TASK, exception.errorDetail().get("taskId"));
        assertNull(exception.errorDetail().get("providerStatus"));
        verifyNothingSubmitted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "expired", "cancelled"})
    void execute_PendingTaskThatAlreadyFailed_ResubmitsWithoutAsking(String oldStatus) throws Exception {
        givenPendingTask(OLD_TASK, "same prompt");
        when(agentPlanClient.getVideoTask(config, OLD_TASK)).thenReturn(Map.of("status", oldStatus));
        when(agentPlanClient.createVideoTask(eq(config), anyString(), any(), anyInt(), any(), any(), any()))
                .thenReturn(NEW_TASK);
        givenNewTaskSucceeds();

        Map<String, Object> result = executor.execute(node, inputWithKey("same prompt"));

        assertEquals(NEW_TASK, result.get("taskId"));
        verify(checkpointService).markFailed(KEY, OLD_TASK);
        verify(checkpointService).recordSubmitted(eq(KEY), eq(NEW_TASK), anyString());
        // already terminal on the provider side: nothing left to cancel
        verify(agentPlanClient, never()).cancelVideoTask(any(), anyString());
    }

    @Test
    void execute_ChoiceContinue_PollsOldTaskWithoutSubmitting() throws Exception {
        givenPendingTask(OLD_TASK, "same prompt");
        when(agentPlanClient.getVideoTask(config, OLD_TASK))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);
        Map<String, Object> input = inputWithKey("same prompt");
        input.put("videoTaskAction", "continue");

        Map<String, Object> result = executor.execute(node, input, progressCallback);

        assertEquals(OLD_TASK, result.get("taskId"));
        assertEquals(PERSISTED_URL, result.get("videoUrl"));
        verifyNothingSubmitted();
        // only the normal poll; no extra status peek before it
        verify(agentPlanClient, times(1)).getVideoTask(config, OLD_TASK);
        verify(checkpointService).markSucceeded(KEY, OLD_TASK);
    }

    @Test
    void execute_ChoiceContinue_OldTaskFailsAtProvider_MarksItFailedSoNextResumeResubmits() throws Exception {
        givenPendingTask(OLD_TASK, "same prompt");
        when(agentPlanClient.getVideoTask(config, OLD_TASK)).thenReturn(Map.of("status", "expired"));
        Map<String, Object> input = inputWithKey("same prompt");
        input.put("videoTaskAction", "continue");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> executor.execute(node, input));

        assertTrue(exception.getMessage().contains("视频生成失败"));
        verify(checkpointService).markFailed(KEY, OLD_TASK);
    }

    @Test
    void execute_ChoiceRegenerate_SubmitsNewTaskAndOverwritesRecord() throws Exception {
        when(checkpointService.findPending(KEY))
                .thenReturn(Optional.of(pendingTask(OLD_TASK, "same prompt")));
        givenTaskCreated(NEW_TASK);
        givenNewTaskSucceeds();
        Map<String, Object> input = inputWithKey("same prompt");
        input.put("videoTaskAction", "regenerate");

        Map<String, Object> result = executor.execute(node, input);

        assertEquals(NEW_TASK, result.get("taskId"));
        InOrder order = inOrder(agentPlanClient, checkpointService);
        order.verify(agentPlanClient).cancelVideoTask(config, OLD_TASK);
        order.verify(checkpointService).markFailed(KEY, OLD_TASK);
        order.verify(agentPlanClient).createVideoTask(config, "same prompt", null, 5, null, "adaptive", null);
        order.verify(checkpointService).recordSubmitted(eq(KEY), eq(NEW_TASK), anyString());
        verify(agentPlanClient, never()).getVideoTask(config, OLD_TASK);
        assertEquals(Map.of("taskId", OLD_TASK, "cancelled", true), result.get("previousTask"));
    }

    @Test
    void execute_ChoiceRegenerate_CancelRejected_StillRegeneratesAndReportsIt() throws Exception {
        when(checkpointService.findPending(KEY))
                .thenReturn(Optional.of(pendingTask(OLD_TASK, "same prompt")));
        doThrow(new IOException("Agent Plan 调用失败, HTTP 400: task is running"))
                .when(agentPlanClient).cancelVideoTask(config, OLD_TASK);
        givenTaskCreated(NEW_TASK);
        givenNewTaskSucceeds();
        Map<String, Object> input = inputWithKey("same prompt");
        input.put("videoTaskAction", "regenerate");

        Map<String, Object> result = executor.execute(node, input, progressCallback);

        assertEquals(NEW_TASK, result.get("taskId"));
        Map<?, ?> previous = (Map<?, ?>) result.get("previousTask");
        assertEquals(OLD_TASK, previous.get("taskId"));
        assertEquals(false, previous.get("cancelled"));
        assertTrue(previous.get("message").toString().contains("HTTP 400"));
        // not known to be dead, so it must not be recorded as failed
        verify(checkpointService, never()).markFailed(anyString(), anyString());
        verify(checkpointService).recordSubmitted(eq(KEY), eq(NEW_TASK), anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"continue", "regenerate"})
    void execute_PendingTaskWithChangedPrompt_ResubmitsWithNewPromptWithoutAsking(String action) throws Exception {
        when(checkpointService.findPending(KEY))
                .thenReturn(Optional.of(pendingTask(OLD_TASK, "old prompt")));
        givenTaskCreated(NEW_TASK);
        givenNewTaskSucceeds();
        Map<String, Object> input = inputWithKey("new prompt");
        if (action != null) {
            input.put("videoTaskAction", action);
        }

        Map<String, Object> result = executor.execute(node, input);

        assertEquals(NEW_TASK, result.get("taskId"));
        verify(agentPlanClient).cancelVideoTask(config, OLD_TASK);
        verify(agentPlanClient).createVideoTask(config, "new prompt", null, 5, null, "adaptive", null);
        verify(agentPlanClient, never()).getVideoTask(config, OLD_TASK);
    }

    @Test
    void execute_FailureWhilePolling_MarksCheckpointFailed() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", "failed"));

        assertThrows(IllegalStateException.class, () -> executor.execute(node, inputWithKey("will fail")));

        verify(checkpointService).markFailed(KEY, TASK_ID);
        verify(checkpointService, never()).markSucceeded(anyString(), anyString());
    }

    @Test
    void execute_SucceededWithoutVideoUrl_MarksCheckpointFailed() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", "succeeded"));

        assertThrows(IllegalStateException.class, () -> executor.execute(node, inputWithKey("no url")));

        verify(checkpointService).markFailed(KEY, TASK_ID);
    }

    @Test
    void execute_TimeoutWhilePolling_LeavesCheckpointPending() throws Exception {
        givenTaskCreated(TASK_ID);
        when(agentPlanClient.getVideoTask(config, TASK_ID)).thenReturn(Map.of("status", "running"));

        assertThrows(IllegalStateException.class, () -> executor.execute(node, inputWithKey("slow video")));

        verify(checkpointService).recordSubmitted(eq(KEY), eq(TASK_ID), anyString());
        verify(checkpointService, never()).markFailed(anyString(), anyString());
        verify(checkpointService, never()).markSucceeded(anyString(), anyString());
    }

    @Test
    void execute_CheckpointStoreFailures_DoNotBreakGeneration() throws Exception {
        when(checkpointService.findPending(KEY)).thenThrow(new RuntimeException("db down"));
        doThrow(new RuntimeException("db down")).when(checkpointService).recordSubmitted(anyString(), anyString(), anyString());
        doThrow(new RuntimeException("db down")).when(checkpointService).markSucceeded(anyString(), anyString());
        givenTaskCreated(TASK_ID);
        givenImmediateSuccess();

        Map<String, Object> result = executor.execute(node, inputWithKey("db is down"));

        assertEquals("succeeded", result.get("status"));
        assertEquals(PERSISTED_URL, result.get("videoUrl"));
    }

    @Test
    void fingerprint_ChangesWhenAnyRequestFieldChanges() {
        String base = VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 5, "720p", "16:9", "zoom");

        assertEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 5, "720p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m2", "p", "ref", 5, "720p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p2", "ref", 5, "720p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref2", 5, "720p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 6, "720p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 5, "1080p", "16:9", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 5, "720p", "1:1", "zoom"));
        assertNotEquals(base, VideoGenerateNodeExecutor.fingerprint("m", "p", "ref", 5, "720p", "16:9", "pan"));
        // a null field must not collide with a neighbouring field that happens to hold the same text
        assertNotEquals(VideoGenerateNodeExecutor.fingerprint("m", "ab", null, 5, null, null, null),
                VideoGenerateNodeExecutor.fingerprint("m", "a", "b", 5, null, null, null));
    }

    @Test
    void getSupportedNodeType_ReturnsVideoGenerate() {
        assertEquals("video_generate", executor.getSupportedNodeType());
    }

    private void givenTaskCreated(String taskId) throws Exception {
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(eq(config), anyString(), any(), anyInt(), any(), any(), any()))
                .thenReturn(taskId);
    }

    private void givenImmediateSuccess() throws Exception {
        when(agentPlanClient.getVideoTask(config, TASK_ID))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);
    }

    private Map<String, Object> inputWithKey(String prompt) {
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", prompt);
        input.put("__idempotencyKey__", KEY);
        return input;
    }

    private VideoGenerationTask pendingTask(String taskId, String prompt) {
        VideoGenerationTask task = new VideoGenerationTask();
        task.setIdempotencyKey(KEY);
        task.setTaskId(taskId);
        task.setFingerprint(VideoGenerateNodeExecutor.fingerprint(
                "test-video-model", prompt, null, 5, null, "adaptive", null));
        task.setStatus("PENDING");
        return task;
    }

    private void givenPendingTask(String taskId, String prompt) {
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(checkpointService.findPending(KEY)).thenReturn(Optional.of(pendingTask(taskId, prompt)));
    }

    private void givenNewTaskSucceeds() throws Exception {
        when(agentPlanClient.getVideoTask(config, NEW_TASK))
                .thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn(PERSISTED_URL);
    }

    private void verifyNothingSubmitted() throws Exception {
        verify(agentPlanClient, never()).cancelVideoTask(any(), anyString());
        verify(agentPlanClient, never()).createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any());
        verify(checkpointService, never()).recordSubmitted(anyString(), anyString(), anyString());
    }
}
