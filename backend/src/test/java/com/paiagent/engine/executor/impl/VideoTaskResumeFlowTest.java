package com.paiagent.engine.executor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.WorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.ExecutionVariableMapper;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import com.paiagent.support.FlowBuilder;
import com.paiagent.support.PaiAgentSpringTest;
import com.paiagent.support.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用真引擎、真快照、真 MySQL 走一遍用户场景：轮询超时 → 续跑被反问 → 按选择继续等待/重新生成。
 * 只有外部服务（火山引擎客户端、配置解析、MinIO）是 Mock。
 */
@PaiAgentSpringTest
class VideoTaskResumeFlowTest {

    private static final long FLOW_ID = 9_100_007L;
    private static final String VIDEO_URL = "https://origin.example/video.mp4";

    @MockitoBean
    private VolcengineAgentPlanClient client;
    @MockitoBean
    private AgentPlanConfigResolver configResolver;
    @MockitoBean
    private MinioService minioService;

    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private VideoGenerateNodeExecutor videoExecutor;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;
    @Autowired
    private ExecutionVariableMapper variableMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ResolvedAgentPlanConfig config = new ResolvedAgentPlanConfig(
            1L, "volcengine", "https://ark.example/api/v3", "test-key", "test-video-model",
            null, null, "test-video-model", false);

    @BeforeEach
    void setUp() throws Exception {
        videoExecutor.setPollIntervalMs(0);
        when(configResolver.resolve(any(), eq("video"))).thenReturn(config);
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn("https://minio.example/v.mp4");
    }

    @AfterEach
    void cleanup() {
        videoExecutor.setPollIntervalMs(5_000);
        for (ExecutionRecord record : recordMapper.selectList(
                new LambdaQueryWrapper<ExecutionRecord>().eq(ExecutionRecord::getFlowId, FLOW_ID))) {
            jdbcTemplate.update("DELETE FROM video_generation_task WHERE idempotency_key = ?", record.getId() + ":vid");
            variableMapper.deleteByExecutionId(record.getId());
        }
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    private Workflow workflowWithPrompt(String prompt) {
        return new FlowBuilder()
                .input("in")
                .node("vid", "video_generate", Map.of("prompt", prompt))
                .output("out")
                .edge("in", "vid").edge("vid", "out")
                .build(FLOW_ID, "dag");
    }

    private ExecutionResponse resume(Workflow workflow, Long executionId, String videoTaskAction) {
        ResumeExecutionRequest request = new ResumeExecutionRequest();
        request.setSkipSuccessNodes(true);
        if (videoTaskAction != null) {
            request.setModifiedVariables(Map.of("videoTaskAction", videoTaskAction));
        }
        return engine.resumeExecution(workflow, executionId, request, null);
    }

    private ExecutionResponse.NodeResult videoNode(ExecutionResponse response) {
        return response.getNodeResults().stream()
                .filter(node -> "vid".equals(node.getNodeId()))
                .findFirst().orElseThrow();
    }

    private ExecutionResponse timedOutFirstRun(Workflow workflow, String taskId) throws Exception {
        when(client.createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn(taskId);
        when(client.getVideoTask(config, taskId)).thenReturn(Map.of("status", "running"));
        ExecutionResponse first = engine.execute(workflow, "hello");
        assertEquals("FAILED", first.getStatus());
        assertTrue(videoNode(first).getError().contains("视频生成超时"));
        assertNull(videoNode(first).getErrorCode(), "an ordinary failure carries no structured error");
        return first;
    }

    @Test
    void timeout_thenResumeWithoutChoice_asks_thenContinueReusesTheSameTask() throws Exception {
        Workflow workflow = workflowWithPrompt("a cat");
        ExecutionResponse first = timedOutFirstRun(workflow, "t-1");

        ExecutionResponse asked = resume(workflow, first.getExecutionId(), null);

        assertEquals("FAILED", asked.getStatus());
        ExecutionResponse.NodeResult askedNode = videoNode(asked);
        assertEquals("VIDEO_TASK_PENDING", askedNode.getErrorCode());
        assertEquals("t-1", askedNode.getErrorDetail().get("taskId"));
        assertEquals("running", askedNode.getErrorDetail().get("providerStatus"));
        verify(client, never()).cancelVideoTask(any(), anyString());
        verify(client, times(1)).createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any());

        // the task finished while the user was deciding
        when(client.getVideoTask(config, "t-1")).thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        ExecutionResponse continued = resume(workflow, first.getExecutionId(), "continue");

        assertEquals("SUCCESS", continued.getStatus(), continued.getErrorMessage());
        assertTrue(videoNode(continued).getOutput().contains("t-1"));
        verify(client, times(1)).createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void timeout_thenResumeWithRegenerate_submitsANewTask() throws Exception {
        Workflow workflow = workflowWithPrompt("a cat");
        ExecutionResponse first = timedOutFirstRun(workflow, "t-1");

        when(client.createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn("t-2");
        when(client.getVideoTask(config, "t-2")).thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        ExecutionResponse regenerated = resume(workflow, first.getExecutionId(), "regenerate");

        assertEquals("SUCCESS", regenerated.getStatus(), regenerated.getErrorMessage());
        assertTrue(videoNode(regenerated).getOutput().contains("t-2"));
        verify(client).cancelVideoTask(config, "t-1");
        verify(client, times(2)).createVideoTask(any(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void timeout_thenPromptEditedAndResumed_resubmitsWithTheNewPromptWithoutAsking() throws Exception {
        ExecutionResponse first = timedOutFirstRun(workflowWithPrompt("a cat"), "t-1");

        when(client.createVideoTask(any(), eq("a dog"), any(), anyInt(), any(), any(), any())).thenReturn("t-2");
        when(client.getVideoTask(config, "t-2")).thenReturn(Map.of("status", "succeeded", "videoUrl", VIDEO_URL));
        ExecutionResponse resumed = resume(workflowWithPrompt("a dog"), first.getExecutionId(), null);

        assertEquals("SUCCESS", resumed.getStatus(), resumed.getErrorMessage());
        assertTrue(videoNode(resumed).getOutput().contains("t-2"));
        verify(client).cancelVideoTask(config, "t-1");
        verify(client).createVideoTask(any(), eq("a dog"), any(), anyInt(), any(), any(), any());
    }
}
