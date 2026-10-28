package com.paiagent.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.cancel.CancelToken;
import com.paiagent.engine.cancel.ExecutionCancellations;
import com.paiagent.engine.cancel.ExecutionCanceller;
import com.paiagent.engine.langgraph.LangGraphWorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取消执行：两个引擎行为一致；节点被及时中断；已完成的节点保留，取消后可以续跑；重复取消幂等；
 * 已经结束的执行不能取消；不响应中断的节点期间状态是 CANCELLING，而不是假装已经取消。
 */
@PaiAgentSpringTest
class ExecutionCancellationTest {

    private static final long FLOW_ID = 9_100_007L;

    @Autowired
    private WorkflowEngine dagEngine;
    @Autowired
    private LangGraphWorkflowEngine langGraphEngine;
    @Autowired
    private ExecutionCanceller canceller;
    @Autowired
    private ExecutionCancellations cancellations;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;

    @BeforeEach
    void setUp() {
        TestNodeExecutors.reset();
        cleanup();
    }

    @AfterEach
    void cleanup() {
        for (ExecutionRecord record : recordMapper.selectList(
                new LambdaQueryWrapper<ExecutionRecord>().eq(ExecutionRecord::getFlowId, FLOW_ID))) {
            snapshotMapper.deleteByExecutionId(record.getId());
            recordMapper.deleteById(record.getId());
        }
    }

    /** 串行链：in → fast → slow(慢) → after → out。取消发生在 slow 运行期间。 */
    private Workflow chain(String engine, long slowMs) {
        return new FlowBuilder()
                .input("in").task("fast").task("slow", slowMs, 0).task("after").output("out")
                .edge("in", "fast").edge("fast", "slow").edge("slow", "after").edge("after", "out")
                .build(FLOW_ID, engine);
    }

    private static final class Running {
        final CompletableFuture<ExecutionResponse> result;
        final CompletableFuture<Long> executionId;

        Running(CompletableFuture<ExecutionResponse> result, CompletableFuture<Long> executionId) {
            this.result = result;
            this.executionId = executionId;
        }
    }

    private Running start(WorkflowExecutor engine, Workflow workflow) {
        CompletableFuture<Long> executionId = new CompletableFuture<>();
        Consumer<ExecutionEvent> callback = event -> {
            if ("WORKFLOW_START".equals(event.getEventType()) && event.getData() instanceof Number number) {
                executionId.complete(number.longValue());
            }
        };
        CompletableFuture<ExecutionResponse> result = CompletableFuture.supplyAsync(
                () -> engine.executeWithCallback(workflow, "hello", callback));
        return new Running(result, executionId);
    }

    private void waitUntilNodeStarted(String nodeId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (TestNodeExecutors.invocations(nodeId) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(TestNodeExecutors.invocations(nodeId) > 0, "节点 " + nodeId + " 没有按时开始");
    }

    private void cancelsAnInFlightNodeAndStopsTheRest(String engineType, WorkflowExecutor engine) throws Exception {
        Workflow workflow = chain(engineType, 20_000);
        Running running = start(engine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("slow");

        long cancelledAt = System.nanoTime();
        ExecutionCanceller.Outcome outcome = canceller.cancel(FLOW_ID, executionId);
        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);

        System.out.printf("[取消/%s] 节点本来要睡 20000ms，取消后 %dms 内整个执行结束，受理时状态=%s，最终状态=%s%n",
                engineType, elapsedMs, outcome.status(), response.getStatus());
        assertEquals("CANCELLING", outcome.status());
        assertEquals("CANCELLED", response.getStatus());
        assertTrue(elapsedMs < 3_000, "睡眠中的节点应被中断而不是等满 20 秒，实际 " + elapsedMs + "ms");
        assertTrue(response.getErrorMessage().contains("执行已取消"), response.getErrorMessage());
        assertNull(response.getOutputData(), "被取消的执行没有最终输出");

        assertEquals(0, TestNodeExecutors.invocations("after"), "取消之后的节点不应该再启动");
        assertEquals("CANCELLED", record(executionId).getStatus());
        assertEquals("SUCCESS", snapshot(executionId, "fast").getStatus(), "取消前已完成的节点保留 SUCCESS");
        assertEquals("CANCELLED", snapshot(executionId, "slow").getStatus(), "被中断的节点记为 CANCELLED，不是 FAILED");
        assertNull(snapshot(executionId, "after"), "没启动的节点没有快照");
        assertTrue(response.getNodeResults().stream().anyMatch(r -> "slow".equals(r.getNodeId()) && "CANCELLED".equals(r.getStatus())));
        assertNull(cancellations.find(executionId), "结束后登记表里不应残留");
    }

    @Test
    void dagEngine_cancelInterruptsTheRunningNodeAndSkipsTheRest() throws Exception {
        cancelsAnInFlightNodeAndStopsTheRest("dag", dagEngine);
    }

    @Test
    void langGraphEngine_behavesTheSame() throws Exception {
        cancelsAnInFlightNodeAndStopsTheRest("langgraph", langGraphEngine);
    }

    @Test
    void parallelNodesInTheSameLevel_areAllInterrupted() throws Exception {
        Workflow workflow = new FlowBuilder()
                .input("in").task("a", 20_000, 0).task("b", 20_000, 0).task("after").output("out")
                .edge("in", "a").edge("in", "b").edge("a", "after").edge("b", "after").edge("after", "out")
                .build(FLOW_ID, "dag");
        Running running = start(dagEngine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("a");
        waitUntilNodeStarted("b");

        long start = System.nanoTime();
        canceller.cancel(FLOW_ID, executionId);
        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);

        assertEquals("CANCELLED", response.getStatus());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        assertEquals("CANCELLED", snapshot(executionId, "a").getStatus());
        assertEquals("CANCELLED", snapshot(executionId, "b").getStatus());
        assertEquals(0, TestNodeExecutors.invocations("after"));
    }

    @Test
    void repeatedCancel_isIdempotent() throws Exception {
        Running running = start(dagEngine, chain("dag", 20_000));
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("slow");

        ExecutionCanceller.Outcome first = canceller.cancel(FLOW_ID, executionId);
        ExecutionCanceller.Outcome second = canceller.cancel(FLOW_ID, executionId);
        running.result.get(10, TimeUnit.SECONDS);
        ExecutionCanceller.Outcome third = canceller.cancel(FLOW_ID, executionId);

        assertEquals("CANCELLING", first.status());
        assertTrue(List.of("CANCELLING", "CANCELLED").contains(second.status()));
        assertEquals("CANCELLED", third.status(), "已经取消完的再取消，仍然成功并报告 CANCELLED");
    }

    @Test
    void finishedExecution_cannotBeCancelled() {
        Workflow workflow = new FlowBuilder().input("in").task("only").output("out")
                .edge("in", "only").edge("only", "out").build(FLOW_ID, "dag");
        ExecutionResponse done = dagEngine.execute(workflow, "hello");
        assertEquals("SUCCESS", done.getStatus());

        assertThrows(ExecutionConflictException.class, () -> canceller.cancel(FLOW_ID, done.getExecutionId()));
        assertEquals("SUCCESS", record(done.getExecutionId()).getStatus(), "被拒绝的取消不能改动已有结果");
    }

    @Test
    void statusTransitionsAreGuardedInSql_soARaceCannotOverwriteAFinishedResult() {
        // canceller 里的状态检查和真正的 UPDATE 之间执行可能刚好结束；这时只有 SQL 条件能保护结果
        Workflow workflow = new FlowBuilder().input("in").task("only").output("out")
                .edge("in", "only").edge("only", "out").build(FLOW_ID, "dag");
        ExecutionResponse done = dagEngine.execute(workflow, "hello");
        Long id = done.getExecutionId();

        assertEquals(0, recordMapper.requestCancel(id), "SUCCESS 不能进入 CANCELLING");
        assertEquals(0, recordMapper.cancelOrphan(id, "x"), "SUCCESS 不能被当成孤儿记录取消");
        assertEquals(0, recordMapper.cancelIfCancelling(id, "x"), "只有 CANCELLING 才能被收尾成 CANCELLED");
        assertEquals("SUCCESS", record(id).getStatus());
        assertNotNull(record(id).getOutputData(), "结果必须原样保留");
    }

    @Test
    void unknownExecutionOrWrongWorkflow_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> canceller.cancel(FLOW_ID, 987_654_321L));

        Workflow workflow = new FlowBuilder().input("in").task("only").output("out")
                .edge("in", "only").edge("only", "out").build(FLOW_ID, "dag");
        ExecutionResponse done = dagEngine.execute(workflow, "hello");
        assertThrows(IllegalArgumentException.class, () -> canceller.cancel(FLOW_ID + 1, done.getExecutionId()),
                "不能借别的工作流的编号去取消");
    }

    @Test
    void orphanRunningRecord_isCancelledDirectly() {
        // 进程重启后留下的 RUNNING 记录：登记表里没有它，没有任何线程可以通知，直接收尾
        ExecutionRecord orphan = new ExecutionRecord();
        orphan.setFlowId(FLOW_ID);
        orphan.setInputData("{}");
        orphan.setStatus("RUNNING");
        orphan.setNodeResults("[]");
        orphan.setDuration(0);
        recordMapper.insert(orphan);

        ExecutionCanceller.Outcome outcome = canceller.cancel(FLOW_ID, orphan.getId());

        assertEquals("CANCELLED", outcome.status());
        assertEquals("CANCELLED", record(orphan.getId()).getStatus());
    }

    @Test
    void cancelledExecution_canBeResumed_andOnlyReRunsWhatDidNotFinish() throws Exception {
        Workflow workflow = chain("dag", 20_000);
        Running running = start(dagEngine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("slow");
        canceller.cancel(FLOW_ID, executionId);
        running.result.get(10, TimeUnit.SECONDS);
        assertEquals(1, TestNodeExecutors.invocations("fast"));

        // 续跑时把慢节点换成快的，验证：fast 复用快照不重跑，slow / after 补上
        Workflow quick = chain("dag", 0);
        ExecutionResponse resumed = dagEngine.resumeExecution(quick, executionId, new ResumeExecutionRequest(), null);

        assertEquals("SUCCESS", resumed.getStatus());
        assertEquals(1, TestNodeExecutors.invocations("fast"), "取消前已完成的节点不重复执行");
        assertEquals(1, TestNodeExecutors.invocations("after"));
    }

    @Test
    void resumeWhileCancelling_isRejected() {
        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(FLOW_ID);
        record.setInputData("{\"input\":\"x\"}");
        record.setStatus("CANCELLING");
        record.setNodeResults("[]");
        record.setDuration(0);
        recordMapper.insert(record);

        assertThrows(ExecutionConflictException.class,
                () -> dagEngine.resumeExecution(chain("dag", 0), record.getId(), new ResumeExecutionRequest(), null));
    }

    @Test
    void dagEngine_nodeThatIgnoresInterrupts_keepsTheStatusAtCancelling_untilItReturns() throws Exception {
        stubbornNodeKeepsCancelling("dag", dagEngine);
    }

    @Test
    void langGraphEngine_nodeThatIgnoresInterrupts_keepsTheStatusAtCancelling_untilItReturns() throws Exception {
        stubbornNodeKeepsCancelling("langgraph", langGraphEngine);
    }

    private void stubbornNodeKeepsCancelling(String engineType, WorkflowExecutor engine) throws Exception {
        // 模拟阻塞在不响应中断的网络读上的节点：被中断后吞掉异常，继续干到自己结束
        Workflow workflow = new FlowBuilder()
                .input("in")
                .node("stubborn", "test_task", java.util.Map.of("sleepMs", 1_500L, "ignoreInterrupt", true))
                .task("after").output("out")
                .edge("in", "stubborn").edge("stubborn", "after").edge("after", "out")
                .build(FLOW_ID, engineType);
        Running running = start(engine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("stubborn");

        canceller.cancel(FLOW_ID, executionId);
        Thread.sleep(300);
        assertFalse(running.result.isDone(), "节点还没退出，执行不应该已经结束");
        assertEquals("CANCELLING", record(executionId).getStatus(), "这段时间要如实停在 CANCELLING");

        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);
        // 节点自己跑完了（没有因为中断失败）：它的结果是真实完成的，保留；之后的层因为取消不再启动
        assertEquals("CANCELLED", response.getStatus());
        assertEquals("SUCCESS", snapshot(executionId, "stubborn").getStatus());
        assertEquals(0, TestNodeExecutors.invocations("after"), "取消之后的层不能再启动（" + engineType + "）");
    }

    @Test
    void cancelTooLate_whenNothingIsLeftToSkip_theResultIsStillSuccess() throws Exception {
        // 取消落在最后一个节点运行期间，而该节点不响应中断：它正常完成，后面没有要跳过的层。
        // 取消的含义是"别再往下做"，不是"丢掉已经做完的结果"，所以最终是 SUCCESS 并且保留输出
        Workflow workflow = new FlowBuilder()
                .input("in")
                .node("last", "test_task", java.util.Map.of("sleepMs", 800L, "ignoreInterrupt", true))
                .edge("in", "last")
                .build(FLOW_ID, "dag");
        Running running = start(dagEngine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("last");

        ExecutionCanceller.Outcome outcome = canceller.cancel(FLOW_ID, executionId);
        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);

        assertEquals("CANCELLING", outcome.status(), "受理时它确实还在运行");
        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getOutputData());
        assertEquals("SUCCESS", record(executionId).getStatus(), "落库的最终状态要和返回一致，不能停在 CANCELLING");
    }

    @Test
    void dagEngine_realFailureBeatsCancellation() throws Exception {
        realFailureBeatsCancellation("dag", dagEngine);
    }

    @Test
    void langGraphEngine_realFailureBeatsCancellation() throws Exception {
        realFailureBeatsCancellation("langgraph", langGraphEngine);
    }

    private void realFailureBeatsCancellation(String engineType, WorkflowExecutor engine) throws Exception {
        // 同一层里：slow 会被中断（先被记成"取消"），boom 吞掉中断、过一会儿因为自己的原因失败（后到的真实失败）。
        // 报告的应该是真正出错的 boom，而不是"已取消"
        Workflow workflow = new FlowBuilder()
                .input("in")
                .node("boom", "test_task", java.util.Map.of("sleepMs", 1_000L, "failTimes", 1, "ignoreInterrupt", true))
                .task("slow", 20_000, 0).output("out")
                .edge("in", "boom").edge("in", "slow").edge("boom", "out").edge("slow", "out")
                .build(FLOW_ID, engineType);
        Running running = start(engine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("slow");
        waitUntilNodeStarted("boom");
        canceller.cancel(FLOW_ID, executionId);

        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);

        assertEquals("FAILED", response.getStatus(), engineType);
        assertTrue(response.getErrorMessage().contains("boom"), response.getErrorMessage());
        assertEquals("CANCELLED", snapshot(executionId, "slow").getStatus());
        assertEquals("FAILED", snapshot(executionId, "boom").getStatus());
    }

    @Test
    void nodeThatSwallowsTheInterruptAndThenFailsForItsOwnReason_isFailedNotCancelled() throws Exception {
        Workflow workflow = new FlowBuilder()
                .input("in")
                .node("flaky", "test_task", java.util.Map.of("sleepMs", 600L, "failTimes", 1, "ignoreInterrupt", true))
                .edge("in", "flaky")
                .build(FLOW_ID, "dag");
        Running running = start(dagEngine, workflow);
        Long executionId = running.executionId.get(5, TimeUnit.SECONDS);
        waitUntilNodeStarted("flaky");

        canceller.cancel(FLOW_ID, executionId);
        ExecutionResponse response = running.result.get(10, TimeUnit.SECONDS);

        assertEquals("FAILED", response.getStatus(), "它的失败和取消无关，要保留真实的错误信息");
        assertTrue(response.getErrorMessage().contains("模拟节点失败"), response.getErrorMessage());
        assertEquals("FAILED", snapshot(executionId, "flaky").getStatus());
    }

    @Test
    void cancelToken_doesNotLeakAnInterruptFlagIntoThePoolThread() throws Exception {
        CancelToken token = new CancelToken();
        AtomicReference<Boolean> flagAfterExit = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            token.enter();
            try {
                // 不用 sleep（sleep 抛 InterruptedException 时会顺手清掉中断标志，测不到我们要测的东西）：
                // 空转直到看到中断标志，模拟"标志还留在线程上就结束了节点"
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            } finally {
                assertTrue(token.interruptedByCancel());
                token.exit();
                flagAfterExit.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        Thread.sleep(100);

        token.cancel();
        worker.join(5_000);

        assertEquals(Boolean.FALSE, flagAfterExit.get(), "回到线程池的线程不能带着中断标志");
    }

    private ExecutionRecord record(Long id) {
        return recordMapper.selectById(id);
    }

    private ExecutionSnapshot snapshot(Long executionId, String nodeId) {
        return snapshotMapper.selectByExecutionIdAndNodeId(executionId, nodeId);
    }
}
