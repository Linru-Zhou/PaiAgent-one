package com.paiagent.engine.langgraph;

import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.WorkflowEngine;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangGraph 引擎的并行：用 LangGraph4j 原生并行分支（按层加屏障）真正并发执行，
 * 并且在"原生并行分支不支持"的拓扑上（分支里串多个节点、分支里有条件节点）也与 DAG 引擎行为一致。
 */
@PaiAgentSpringTest
class LangGraphParallelTest {

    private static final long FLOW_ID = 9_100_007L;

    @Autowired
    private LangGraphWorkflowEngine langGraph;
    @Autowired
    private WorkflowEngine dag;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;

    @BeforeEach
    void setUp() {
        TestNodeExecutors.reset();
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(dag, "parallelEnabled", true);
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    private Workflow fanOut(long sleepMs, int failTimesOfA) {
        return new FlowBuilder().input("in")
                .task("a", sleepMs, failTimesOfA).task("b", sleepMs, 0).task("c", sleepMs, 0)
                .output("out")
                .edge("in", "a").edge("in", "b").edge("in", "c")
                .edge("a", "out").edge("b", "out").edge("c", "out")
                .build(FLOW_ID, "langgraph");
    }

    @Test
    void independentNodesReallyRunConcurrentlyOnTheLangGraphEngine() {
        Workflow workflow = fanOut(500, 0);

        long start = System.nanoTime();
        ExecutionResponse response = langGraph.execute(workflow, "x");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        List<TestNodeExecutors.Span> spans = TestNodeExecutors.spans();
        long latestStart = spans.stream().mapToLong(TestNodeExecutors.Span::startNanos).max().orElse(0);
        long earliestEnd = spans.stream().mapToLong(TestNodeExecutors.Span::endNanos).min().orElse(0);
        long threads = spans.stream().map(TestNodeExecutors.Span::thread).distinct().count();
        System.out.printf("[LangGraph并行] 扇出 3×500ms：耗时=%dms（串行应≥1500ms），使用线程数=%d%n", elapsedMs, threads);

        assertEquals("SUCCESS", response.getStatus());
        assertTrue(latestStart < earliestEnd, "三个节点的执行区间应当重叠");
        assertTrue(elapsedMs < 1100, "并行应约等于单个节点耗时，实际 " + elapsedMs + "ms");
        assertTrue(threads >= 2, "应当使用多个线程");
    }

    @Test
    void parallelSwitchOffFallsBackToSerialOnTheLangGraphEngine() {
        Workflow workflow = fanOut(300, 0);
        Object nodeAdapter = ReflectionTestUtils.getField(ReflectionTestUtils.getField(langGraph, "graphBuilder"), "nodeAdapter");
        Object original = ReflectionTestUtils.getField(nodeAdapter, "parallelEnabled");
        try {
            ReflectionTestUtils.setField(nodeAdapter, "parallelEnabled", false);
            long start = System.nanoTime();
            assertEquals("SUCCESS", langGraph.execute(workflow, "x").getStatus());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs >= 900, "关闭并行后应串行（≈3×300ms），实际 " + elapsedMs + "ms");
        } finally {
            ReflectionTestUtils.setField(nodeAdapter, "parallelEnabled", original);
        }
    }

    /** LangGraph4j 原生并行分支不支持"分支里串多个节点 + 分支里有条件节点"，这里验证按层加屏障后行为与 DAG 引擎一致。 */
    @Test
    void topologiesThatNativeParallelBranchesRejectStillMatchTheDagEngine() {
        // in -> a -> a2 -> out ; in -> b(condition) -> (x | y) -> out ; in -> c -> out   （分支长短不一、分支里带条件）
        FlowBuilder builder = new FlowBuilder().input("in")
                .task("a").task("a2")
                .condition("b", "hit", "input", "contains", "x")
                .task("x").task("y")
                .task("c")
                .output("out")
                .edge("in", "a").edge("a", "a2").edge("a2", "out")
                .edge("in", "b").edge("b", "x", "hit").edge("b", "y", "default").edge("x", "out").edge("y", "out")
                .edge("in", "c").edge("c", "out");

        ExecutionResponse viaDag = dag.execute(builder.build(FLOW_ID, "dag"), "has x inside");
        ExecutionResponse viaLg = langGraph.execute(builder.build(FLOW_ID, "langgraph"), "has x inside");

        assertEquals("SUCCESS", viaDag.getStatus());
        assertEquals(viaDag.getStatus(), viaLg.getStatus());
        assertEquals(describe(viaDag), describe(viaLg), "两个引擎的节点执行集合、状态与输出必须一致");
        assertEquals(viaDag.getOutputData(), viaLg.getOutputData(), "最终输出必须一致");
        assertTrue(describe(viaLg).stream().anyMatch(s -> s.startsWith("x:")), "命中分支 x 应执行");
        assertTrue(describe(viaLg).stream().noneMatch(s -> s.startsWith("y:")), "未命中分支 y 不应执行");
    }

    @Test
    void oneParallelBranchFails_siblingsFinishAndAreReusedOnResume() {
        Workflow workflow = fanOut(200, 1); // a 第一次调用失败

        ExecutionResponse first = langGraph.execute(workflow, "x");
        assertEquals("FAILED", first.getStatus());
        assertTrue(first.getErrorMessage().contains("a"), first.getErrorMessage());
        assertEquals(1, TestNodeExecutors.invocations("b"), "同层兄弟节点 b 应该照常跑完");
        assertEquals(1, TestNodeExecutors.invocations("c"));
        assertNull(snapshotMapper.selectByExecutionIdAndNodeId(first.getExecutionId(), "out"), "失败后后续层不应启动");

        // 续跑由 DAG 调度器基于快照完成（快照与引擎无关）
        ExecutionResponse resumed = dag.resumeExecution(workflow, first.getExecutionId(), new ResumeExecutionRequest(), null);
        assertEquals("SUCCESS", resumed.getStatus());
        assertEquals(2, TestNodeExecutors.invocations("a"), "只有失败的 a 被重跑");
        assertEquals(1, TestNodeExecutors.invocations("b"), "已成功的兄弟节点不应被重复执行");
        assertEquals(1, TestNodeExecutors.invocations("c"));
    }

    private static List<String> describe(ExecutionResponse response) {
        return response.getNodeResults().stream()
                .map(r -> r.getNodeId() + ":" + r.getStatus() + ":" + r.getOutput())
                .collect(Collectors.toList());
    }
}
