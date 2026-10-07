package com.paiagent.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.support.FlowBuilder;
import com.paiagent.support.PaiAgentSpringTest;
import com.paiagent.support.TestNodeExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.paiagent.entity.ExecutionSnapshot;
import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行状态一致性：并发续跑只能有一个成功、状态流转受保护、快照不重复。
 */
@PaiAgentSpringTest
class ExecutionConsistencyTest {

    private static final long FLOW_ID = 9_100_001L;

    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;
    @Autowired
    private StaleExecutionReaper reaper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestNodeExecutors.reset();
        cleanup();
    }

    @AfterEach
    void cleanup() {
        List<ExecutionRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<ExecutionRecord>().eq(ExecutionRecord::getFlowId, FLOW_ID));
        for (ExecutionRecord record : records) {
            snapshotMapper.deleteByExecutionId(record.getId());
            recordMapper.deleteById(record.getId());
        }
    }

    private Workflow flakyWorkflow() {
        return new FlowBuilder()
                .input("in").task("flaky", 300, 1).output("out")
                .edge("in", "flaky").edge("flaky", "out")
                .build(FLOW_ID, "dag");
    }

    @Test
    void concurrentResume_onlyOneRequestReRunsTheFailedNode() throws Exception {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");
        assertEquals("FAILED", first.getStatus(), "第一次执行应在 flaky 节点失败");
        assertEquals(1, TestNodeExecutors.invocations("flaky"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                ready.countDown();
                go.await();
                try {
                    ExecutionResponse response = engine.resumeExecution(workflow, first.getExecutionId(),
                            new ResumeExecutionRequest(), null);
                    if ("SUCCESS".equals(response.getStatus())) {
                        succeeded.incrementAndGet();
                    }
                } catch (ExecutionConflictException e) {
                    conflicted.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        int reRuns = TestNodeExecutors.invocations("flaky") - 1;
        System.out.printf("[并发续跑] 并发请求=%d, 实际重新执行 flaky 节点次数=%d, 成功=%d, 被拒绝(冲突)=%d%n",
                threads, reRuns, succeeded.get(), conflicted.get());

        assertEquals(1, reRuns, "同一次执行并发续跑时，失败节点只允许被重新执行一次");
        assertEquals(1, succeeded.get());
        assertEquals(threads - 1, conflicted.get());
    }

    @Test
    void resumeRunningExecution_isRejected() {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");
        ExecutionRecord record = recordMapper.selectById(first.getExecutionId());
        record.setStatus("RUNNING");
        recordMapper.updateById(record);

        assertThrows(ExecutionConflictException.class, () ->
                engine.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null));
    }

    @Test
    void snapshotsAreUniquePerExecutionAndNode() {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");
        engine.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null);

        long total = snapshotMapper.selectByExecutionId(first.getExecutionId()).size();
        long distinct = snapshotMapper.selectByExecutionId(first.getExecutionId()).stream()
                .map(s -> s.getNodeId()).distinct().count();
        assertEquals(distinct, total, "同一执行内每个节点只应有一条快照");
    }

    @Test
    void databaseRejectsDuplicateSnapshotForSameExecutionAndNode() {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");

        ExecutionSnapshot duplicate = new ExecutionSnapshot();
        duplicate.setExecutionId(first.getExecutionId());
        duplicate.setFlowId(FLOW_ID);
        duplicate.setNodeId("flaky");
        duplicate.setNodeType("test_task");
        duplicate.setStatus("RUNNING");

        assertThrows(DuplicateKeyException.class, () -> snapshotMapper.insert(duplicate),
                "唯一约束 uk_execution_node 应该拒绝同一执行内重复的节点快照");
    }

    @Test
    void reaperMarksStaleRunningExecutionAsFailedSoItCanBeResumed() {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");

        // 模拟进程崩溃：记录和节点快照都停在 RUNNING，且心跳是两小时前
        jdbcTemplate.update("UPDATE execution_record SET status = 'RUNNING', updated_at = NOW() - INTERVAL 2 HOUR WHERE id = ?",
                first.getExecutionId());
        jdbcTemplate.update("UPDATE execution_snapshot SET status = 'RUNNING' WHERE execution_id = ? AND node_id = 'flaky'",
                first.getExecutionId());

        assertThrows(ExecutionConflictException.class, () ->
                engine.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null),
                "回收之前，RUNNING 的执行不能被续跑");

        int reaped = reaper.reapStale(Duration.ofMinutes(30));
        assertTrue(reaped >= 1);
        assertEquals("FAILED", recordMapper.selectById(first.getExecutionId()).getStatus());
        assertEquals("FAILED", snapshotMapper.selectByExecutionIdAndNodeId(first.getExecutionId(), "flaky").getStatus());

        ExecutionResponse resumed = engine.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null);
        assertEquals("SUCCESS", resumed.getStatus(), "回收后可以从断点续跑成功");
    }

    @Test
    void reaperLeavesFreshRunningExecutionAlone() {
        Workflow workflow = flakyWorkflow();
        ExecutionResponse first = engine.execute(workflow, "hello");
        jdbcTemplate.update("UPDATE execution_record SET status = 'RUNNING' WHERE id = ?", first.getExecutionId());

        reaper.reapStale(Duration.ofMinutes(30));
        assertEquals("RUNNING", recordMapper.selectById(first.getExecutionId()).getStatus(),
                "刚刚有心跳的 RUNNING 执行不应被回收");
    }
}
