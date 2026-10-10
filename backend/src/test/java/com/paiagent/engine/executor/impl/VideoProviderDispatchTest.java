package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.DashScopeMediaClient;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VideoTaskCheckpointService;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 视频节点按配置的供应商选客户端：通义走通义，火山仍走火山；两者共用同一套任务级恢复逻辑。 */
class VideoProviderDispatchTest {

    private final AgentPlanConfigResolver resolver = mock(AgentPlanConfigResolver.class);
    private final VolcengineAgentPlanClient volcengine = mock(VolcengineAgentPlanClient.class);
    private final DashScopeMediaClient dashScope = mock(DashScopeMediaClient.class);
    private final MinioService minio = mock(MinioService.class);
    private final VideoTaskCheckpointService checkpoints = mock(VideoTaskCheckpointService.class);

    private VideoGenerateNodeExecutor executor() {
        VideoGenerateNodeExecutor executor = new VideoGenerateNodeExecutor(resolver, volcengine, minio, checkpoints);
        ReflectionTestUtils.setField(executor, "dashScopeClient", dashScope);
        executor.setPollIntervalMs(1);
        return executor;
    }

    private WorkflowNode node() {
        WorkflowNode node = new WorkflowNode();
        node.setId("clip");
        node.setType("video_generate");
        Map<String, Object> data = new HashMap<>();
        data.put("prompt", "镜头缓慢推进");
        node.setData(data);
        return node;
    }

    private ResolvedAgentPlanConfig config(String provider) {
        return new ResolvedAgentPlanConfig(1L, provider, "https://dashscope.aliyuncs.com/api/v1", "k", "wan2.2-t2v-plus", null, null, "wan2.2-t2v-plus", false);
    }

    @Test
    void qwenProviderUsesTheDashScopeClientAndKeepsTheCheckpoint() throws Exception {
        when(resolver.resolve(any(), eq("video"))).thenReturn(config("qwen"));
        when(checkpoints.findPending(anyString())).thenReturn(Optional.empty());
        when(dashScope.createVideoTask(any(), anyString(), isNull(), anyInt(), any(), any(), any())).thenReturn("task-9");
        when(dashScope.getVideoTask(any(), eq("task-9"))).thenReturn(Map.of("status", "succeeded", "videoUrl", "http://cdn/v.mp4"));
        when(minio.uploadFromUrl(eq("http://cdn/v.mp4"), anyString(), eq("video/mp4"))).thenReturn("http://minio/videos/v.mp4");

        Map<String, Object> input = new HashMap<>();
        input.put("__idempotencyKey__", "7:clip");
        Map<String, Object> output = executor().execute(node(), input);

        assertEquals("http://minio/videos/v.mp4", output.get("videoUrl"));
        assertEquals("task-9", output.get("taskId"));
        verify(checkpoints).recordSubmitted(eq("7:clip"), eq("task-9"), anyString());
        verify(checkpoints).markSucceeded("7:clip", "task-9");
        verifyNoInteractions(volcengine);
    }

    @Test
    void volcengineProviderStillUsesTheVolcengineClient() throws Exception {
        when(resolver.resolve(any(), eq("video"))).thenReturn(config("volcengine_agent_plan"));
        when(checkpoints.findPending(anyString())).thenReturn(Optional.empty());
        when(volcengine.createVideoTask(any(), anyString(), isNull(), anyInt(), any(), any(), any())).thenReturn("vt-1");
        when(volcengine.getVideoTask(any(), eq("vt-1"))).thenReturn(Map.of("status", "succeeded", "videoUrl", "http://cdn/x.mp4"));
        when(minio.uploadFromUrl(anyString(), anyString(), anyString())).thenReturn("http://minio/x.mp4");

        Map<String, Object> input = new HashMap<>();
        input.put("__idempotencyKey__", "8:clip");
        executor().execute(node(), input);

        verify(dashScope, never()).createVideoTask(any(), any(), any(), anyInt(), any(), any(), any());
        verify(volcengine).createVideoTask(any(), anyString(), isNull(), anyInt(), any(), any(), any());
    }
}
