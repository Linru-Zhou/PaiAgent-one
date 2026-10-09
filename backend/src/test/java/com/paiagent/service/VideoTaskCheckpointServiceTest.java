package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.VideoGenerationTask;
import com.paiagent.mapper.VideoGenerationTaskMapper;
import com.paiagent.support.PaiAgentSpringTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对真实 MySQL：验证建表（SchemaMigrationRunner）和检查点读写的真实 SQL。
 */
@PaiAgentSpringTest
class VideoTaskCheckpointServiceTest {

    @Autowired
    private VideoTaskCheckpointService service;
    @Autowired
    private VideoGenerationTaskMapper mapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String key = "it-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM video_generation_task WHERE idempotency_key = ?", key);
    }

    @Test
    void table_ExistsWithUniqueIdempotencyKey() {
        Integer uniqueIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'video_generation_task'
                  AND COLUMN_NAME = 'idempotency_key' AND NON_UNIQUE = 0
                """, Integer.class);

        assertEquals(1, uniqueIndexes);
    }

    @Test
    void recordSubmitted_InsertsPendingRowThatFindPendingReturns() {
        service.recordSubmitted(key, "task-1", "fp-1");

        VideoGenerationTask pending = service.findPending(key).orElseThrow();
        assertEquals("task-1", pending.getTaskId());
        assertEquals("fp-1", pending.getFingerprint());
        assertEquals(VideoTaskCheckpointService.PENDING, pending.getStatus());
        assertNotNull(pending.getCreatedAt());
    }

    @Test
    void recordSubmitted_Again_OverwritesTheSameRowAndResetsToPending() {
        service.recordSubmitted(key, "task-1", "fp-1");
        service.markSucceeded(key, "task-1");

        service.recordSubmitted(key, "task-2", "fp-2");

        assertEquals(1, mapper.selectCount(new LambdaQueryWrapper<VideoGenerationTask>()
                .eq(VideoGenerationTask::getIdempotencyKey, key)));
        VideoGenerationTask pending = service.findPending(key).orElseThrow();
        assertEquals("task-2", pending.getTaskId());
        assertEquals("fp-2", pending.getFingerprint());
    }

    @Test
    void findPending_IgnoresFinishedAndUnknownKeys() {
        assertTrue(service.findPending(key).isEmpty());

        service.recordSubmitted(key, "task-1", "fp-1");
        service.markSucceeded(key, "task-1");
        assertTrue(service.findPending(key).isEmpty());

        service.recordSubmitted(key, "task-2", "fp-2");
        service.markFailed(key, "task-2");
        assertTrue(service.findPending(key).isEmpty());
    }

    @Test
    void markStatus_OnlyAffectsTheTaskItWasCalledFor() {
        service.recordSubmitted(key, "task-1", "fp-1");
        // "regenerate" replaced the record; a late update for the old task must not touch the new one
        service.recordSubmitted(key, "task-2", "fp-2");

        service.markFailed(key, "task-1");

        assertEquals("task-2", service.findPending(key).orElseThrow().getTaskId());
    }
}
