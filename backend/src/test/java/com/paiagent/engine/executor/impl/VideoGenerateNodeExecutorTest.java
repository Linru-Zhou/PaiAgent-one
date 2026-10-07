package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private AgentPlanConfigResolver configResolver;

    @Mock
    private VolcengineAgentPlanClient agentPlanClient;

    @Mock
    private MinioService minioService;

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
        executor = new VideoGenerateNodeExecutor(configResolver, agentPlanClient, minioService);
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
}
