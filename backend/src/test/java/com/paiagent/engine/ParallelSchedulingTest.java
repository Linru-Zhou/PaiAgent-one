package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.support.FlowBuilder;
import com.paiagent.support.PaiAgentSpringTest;
import com.paiagent.support.TestDb;
import com.paiagent.support.TestNodeExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一层互不依赖节点的并发调度：确实并发、结果与串行一致、失败时兄弟节点结果可复用、事件回调线程安全。
 */
@PaiAgentSpringTest
class ParallelSchedulingTest {

    private static final long FLOW_ID = 9_100_003L;

    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;

    @BeforeEach
    void setUp() {
        TestNodeExecutors.reset();
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
        ReflectionTestUtils.setField(engine, "parallelEnabled", true);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(engine, "parallelEnabled", true);
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    private Workflow fanOut(long sleepMs, int failTimesOfA) {
        return new FlowBuilder().input("in")
                .task("a", sleepMs, failTimesOfA).task("b", sleepMs, 0).task("c", sleepMs, 0)
                .output("out")
                .edge("in", "a").edge("in", "b").edge("in", "c")
                .edge("a", "out").edge("b", "out").edge("c", "out")
                .build(FLOW_ID, "dag");
    }

    private long timed(Workflow workflow, boolean parallel) {
        ReflectionTestUtils.setField(engine, "parallelEnabled", parallel);
        TestNodeExecutors.reset();
        long start = System.nanoTime();
        ExecutionResponse response = engine.execute(workflow, "x");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("SUCCESS", response.getStatus());
        return elapsedMs;
    }

    @Test
    void independentNodesRunConcurrently_andFasterThanSerial() {
        Workflow workflow = fanOut(500, 0);

        long serialMs = timed(workflow, false);
        long parallelMs = timed(workflow, true);

        List<TestNodeExecutors.Span> spans = TestNodeExecutors.spans();
        long latestStart = spans.stream().mapToLong(TestNodeExecutors.Span::startNanos).max().orElse(0);
        long earliestEnd = spans.stream().mapToLong(TestNodeExecutors.Span::endNanos).min().orElse(0);
        long distinctThreads = spans.stream().map(TestNodeExecutors.Span::thread).distinct().count();

        System.out.printf("[并行调度] 扇出 3×500ms：串行=%dms，并行=%dms，加速比=%.2fx，使用线程数=%d%n",
                serialMs, parallelMs, serialMs / (double) parallelMs, distinctThreads);

        assertTrue(latestStart < earliestEnd, "三个节点的执行时间区间应当有重叠（真正并发）");
        assertTrue(serialMs >= 1500, "串行应约等于三者耗时之和，实际 " + serialMs);
        assertTrue(parallelMs < serialMs * 0.6, "并行耗时应显著低于串行: " + parallelMs + " vs " + serialMs);
    }

    @Test
    void snapshotTimestampsHaveMillisecondPrecisionSoTheTimelineCanShowOverlap() {
        Workflow workflow = fanOut(300, 0);
        ExecutionResponse response = engine.execute(workflow, "x");
        assertEquals("SUCCESS", response.getStatus());

        List<ExecutionSnapshot> workers = List.of("a", "b", "c").stream()
                .map(id -> snapshotMapper.selectByExecutionIdAndNodeId(response.getExecutionId(), id))
                .collect(Collectors.toList());

        for (ExecutionSnapshot snapshot : workers) {
            long spanMs = java.time.Duration.between(snapshot.getStartedAt(), snapshot.getCompletedAt()).toMillis();
            assertTrue(Math.abs(spanMs - snapshot.getDuration()) <= 60,
                    snapshot.getNodeId() + " 起止时间差 " + spanMs + "ms 应与记录的耗时 " + snapshot.getDuration() + "ms 接近");
            assertTrue(spanMs >= 300, "300ms 的节点，起止时间差却只有 " + spanMs + "ms，说明时间被截断到了秒");
        }
        long latestStart = workers.stream().mapToLong(w -> w.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()).max().orElse(0);
        long earliestEnd = workers.stream().mapToLong(w -> w.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()).min().orElse(0);
        assertTrue(latestStart < earliestEnd, "三个并行节点在落库的时间轴上应当互相重叠");
    }

    @Test
    void parallelAndSerialProduceTheSameResult() {
        Workflow workflow = fanOut(50, 0);

        ReflectionTestUtils.setField(engine, "parallelEnabled", false);
        ExecutionResponse serial = engine.execute(workflow, "same-input");
        ReflectionTestUtils.setField(engine, "parallelEnabled", true);
        ExecutionResponse parallel = engine.execute(workflow, "same-input");

        assertEquals(serial.getStatus(), parallel.getStatus());
        assertEquals(serial.getOutputData(), parallel.getOutputData(), "最终输出必须一致");
        assertEquals(
                serial.getNodeResults().stream().map(r -> r.getNodeId() + ":" + r.getStatus() + ":" + r.getOutput()).collect(Collectors.toList()),
                parallel.getNodeResults().stream().map(r -> r.getNodeId() + ":" + r.getStatus() + ":" + r.getOutput()).collect(Collectors.toList()),
                "节点结果的顺序和内容必须一致");
    }

    @Test
    void oneBranchFails_siblingsFinishAndAreReusedOnResume() {
        Workflow workflow = fanOut(200, 1); // a 第一次调用失败

        ExecutionResponse first = engine.execute(workflow, "x");
        assertEquals("FAILED", first.getStatus());
        assertTrue(first.getErrorMessage().contains("a"), first.getErrorMessage());
        assertEquals(1, TestNodeExecutors.invocations("b"), "同层兄弟节点 b 应该照常跑完");
        assertEquals(1, TestNodeExecutors.invocations("c"));
        assertNull(snapshotMapper.selectByExecutionIdAndNodeId(first.getExecutionId(), "out"), "失败后后续层不应启动");

        ExecutionSnapshot snapshotB = snapshotMapper.selectByExecutionIdAndNodeId(first.getExecutionId(), "b");
        assertEquals("SUCCESS", snapshotB.getStatus());

        ExecutionResponse resumed = engine.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null);
        assertEquals("SUCCESS", resumed.getStatus());
        assertEquals(2, TestNodeExecutors.invocations("a"), "只有失败的 a 被重跑");
        assertEquals(1, TestNodeExecutors.invocations("b"), "已成功的兄弟节点 b 不应被重复执行");
        assertEquals(1, TestNodeExecutors.invocations("c"), "已成功的兄弟节点 c 不应被重复执行");
        assertEquals("SUCCESS", snapshotMapper.selectByExecutionIdAndNodeId(first.getExecutionId(), "out").getStatus());
        assertEquals(5, resumed.getNodeResults().size(), "续跑后的节点结果应包含全部 5 个节点");
    }

    @Test
    void eventCallbackIsNeverInvokedConcurrently() {
        Workflow workflow = fanOut(100, 0);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<String> types = new java.util.concurrent.CopyOnWriteArrayList<>();

        engine.executeWithCallback(workflow, "x", (ExecutionEvent event) -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5); // 放大并发窗口
            } catch (InterruptedException ignored) {
            }
            types.add(event.getEventType());
            inFlight.decrementAndGet();
        });

        assertEquals(1, maxInFlight.get(), "回调必须被串行化（SseEmitter 不是线程安全的）");
        assertEquals(1, types.stream().filter("WORKFLOW_START"::equals).count());
        assertEquals(1, types.stream().filter("WORKFLOW_COMPLETE"::equals).count());
        assertEquals(5, types.stream().filter("NODE_START"::equals).count());
        assertEquals(5, types.stream().filter("NODE_SUCCESS"::equals).count());
    }

    @Test
    void persistedOutputDoesNotLeakRuntimeFields() {
        ExecutionResponse response = engine.execute(fanOut(10, 0), "x");
        String all = JSON.toJSONString(response);
        assertFalse(all.contains("__idempotencyKey__"));
        assertFalse(all.contains("__executionId__"));
        assertFalse(all.contains("__nodeOutputs__"));
        Map<String, Object> out = JSON.parseObject(response.getOutputData());
        assertNotNull(out.get("output"));
    }
}
