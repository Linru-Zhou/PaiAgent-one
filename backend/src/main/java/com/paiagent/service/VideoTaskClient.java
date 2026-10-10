package com.paiagent.service;

import java.io.IOException;
import java.util.Map;

/**
 * 异步视频生成供应商的统一接口：提交任务 → 轮询 → （可选）取消。
 * 视频节点的任务级恢复（taskId 检查点、继续等待 / 重新生成）只依赖这三个动作，所以火山和通义共用同一套逻辑。
 *
 * getVideoTask 返回的 status 必须能被节点识别：成功含 success/succeed/completed，失败含 fail/error/cancel/expire，
 * 其余视为运行中；videoUrl / coverUrl 为空表示还没有。
 */
public interface VideoTaskClient {

    String createVideoTask(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl,
                           int duration, String resolution, String ratio, String cameraMotion) throws IOException;

    Map<String, Object> getVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException;

    void cancelVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException;
}
