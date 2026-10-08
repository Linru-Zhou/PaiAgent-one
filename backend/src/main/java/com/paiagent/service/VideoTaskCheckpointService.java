package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paiagent.entity.VideoGenerationTask;
import com.paiagent.mapper.VideoGenerationTaskMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 视频任务检查点。幂等键 executionId:nodeId 与执行快照同粒度；同一次执行同一时刻只会有一个请求在跑
 * （执行状态的乐观锁保证），所以这里和知识库的幂等写入一样，用"先查再写"即可。
 */
@Service
public class VideoTaskCheckpointService {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private final VideoGenerationTaskMapper mapper;

    public VideoTaskCheckpointService(VideoGenerationTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** 该幂等键下最近一次提交的任务，且仍未结束（PENDING）时才返回。 */
    public Optional<VideoGenerationTask> findPending(String idempotencyKey) {
        VideoGenerationTask task = find(idempotencyKey);
        return task != null && PENDING.equals(task.getStatus()) ? Optional.of(task) : Optional.empty();
    }

    /** 任务一提交成功就记下来；同一个幂等键只保留最新一次提交。 */
    public void recordSubmitted(String idempotencyKey, String taskId, String fingerprint) {
        VideoGenerationTask task = find(idempotencyKey);
        if (task == null) {
            task = new VideoGenerationTask();
            task.setIdempotencyKey(idempotencyKey);
            task.setTaskId(taskId);
            task.setFingerprint(fingerprint);
            task.setStatus(PENDING);
            mapper.insert(task);
            return;
        }
        task.setTaskId(taskId);
        task.setFingerprint(fingerprint);
        task.setStatus(PENDING);
        mapper.updateById(task);
    }

    public void markSucceeded(String idempotencyKey, String taskId) {
        updateStatus(idempotencyKey, taskId, SUCCEEDED);
    }

    public void markFailed(String idempotencyKey, String taskId) {
        updateStatus(idempotencyKey, taskId, FAILED);
    }

    private VideoGenerationTask find(String idempotencyKey) {
        return mapper.selectOne(new LambdaQueryWrapper<VideoGenerationTask>()
                .eq(VideoGenerationTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
    }

    /** 带上 taskId 条件：只改自己提交的那个任务，不会误伤之后被"重新生成"覆盖的记录。 */
    private void updateStatus(String idempotencyKey, String taskId, String status) {
        mapper.update(null, new LambdaUpdateWrapper<VideoGenerationTask>()
                .eq(VideoGenerationTask::getIdempotencyKey, idempotencyKey)
                .eq(VideoGenerationTask::getTaskId, taskId)
                .set(VideoGenerationTask::getStatus, status));
    }
}
