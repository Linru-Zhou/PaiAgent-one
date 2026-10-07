package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.langgraph.LangGraphWorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双引擎一致性契约：同一份工作流、同一个输入，DAG 引擎和 LangGraph 引擎必须得到等价的结果。
 * 比对维度：最终状态/输出、实际执行了哪些节点、每个节点的状态与输出、失败语义、事件序列、
 * 执行记录与快照是否落库（时间线/断点续跑都依赖它）、非法图的处理方式。
 */
@PaiAgentSpringTest
class EngineContractTest {

    private static final long FLOW_ID = 9_100_005L;

    @Autowired
    private WorkflowEngine dagEngine;
    @Autowired
    private LangGraphWorkflowEngine langGraphEngine;
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
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    /** 一次运行的可观测结果 */
    private static class Outcome {
        String engine;
        ExecutionResponse response;
        List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
        Throwable thrown;

        Map<String, String> nodeStatuses() {
            Map<String, String> map = new TreeMap<>();
            for (ExecutionResponse.NodeResult r : response.getNodeResults()) {
                map.put(r.getNodeId(), r.getStatus());
            }
            return map;
        }

        Map<String, Object> nodeOutputs() {
            Map<String, Object> map = new TreeMap<>();
            for (ExecutionResponse.NodeResult r : response.getNodeResults()) {
                map.put(r.getNodeId(), r.getOutput() == null ? null : JSON.parseObject(r.getOutput()));
            }
            return map;
        }

        Map<String, Object> finalOutput() {
            return response.getOutputData() == null ? null : JSON.parseObject(response.getOutputData());
        }
    }

    private Outcome run(String engineName, Workflow base, String input) {
        Workflow workflow = new Workflow();
        workflow.setId(base.getId());
        workflow.setName(base.getName());
        workflow.setFlowData(base.getFlowData());
        workflow.setEngineType(engineName);

        WorkflowExecutor engine = "dag".equals(engineName) ? dagEngine : langGraphEngine;
        Outcome outcome = new Outcome();
        outcome.engine = engineName;
        try {
            outcome.response = engine.executeWithCallback(workflow, input, outcome.events::add);
        } catch (Throwable t) {
            outcome.thrown = t;
        }
        return outcome;
    }

    private void assertSameOutcome(String scenario, Outcome dag, Outcome lg) {
        assertNull(dag.thrown, scenario + " DAG 引擎不应抛异常: " + dag.thrown);
        assertNull(lg.thrown, scenario + " LangGraph 引擎不应抛异常: " + lg.thrown);
        assertEquals(dag.response.getStatus(), lg.response.getStatus(), scenario + ": 最终状态");
        assertEquals(dag.nodeStatuses(), lg.nodeStatuses(), scenario + ": 实际执行的节点及其状态");
        assertEquals(dag.nodeOutputs(), lg.nodeOutputs(), scenario + ": 每个节点的输出");
        assertEquals(dag.finalOutput(), lg.finalOutput(), scenario + ": 最终输出");
    }

    private Workflow linear() {
        return new FlowBuilder().input("in").task("a").output("out")
                .edge("in", "a").edge("a", "out").build(FLOW_ID, "dag");
    }

    private Workflow fanOutJoin() {
        return new FlowBuilder().input("in").task("a", 100, 0).task("b", 100, 0).output("out")
                .edge("in", "a").edge("in", "b").edge("a", "out").edge("b", "out").build(FLOW_ID, "dag");
    }

    private Workflow conditional() {
        return new FlowBuilder().input("in")
                .condition("cond", "c1", "input", "contains", "转人工")
                .task("human").task("bot").output("out")
                .edge("in", "cond")
                .edge("cond", "human", "c1").edge("cond", "bot", "default")
                .edge("human", "out").edge("bot", "out")
                .build(FLOW_ID, "dag");
    }

    private Workflow failing(int failTimes) {
        return new FlowBuilder().input("in").task("bad", 0, failTimes).output("out")
                .edge("in", "bad").edge("bad", "out").build(FLOW_ID, "dag");
    }

    @Test
    void linearChain_sameResult() {
        Outcome dag = run("dag", linear(), "hello");
        Outcome lg = run("langgraph", linear(), "hello");
        assertSameOutcome("线性链", dag, lg);
        assertEquals("SUCCESS", dag.response.getStatus());
    }

    @Test
    void fanOutJoin_sameResult() {
        Outcome dag = run("dag", fanOutJoin(), "hello");
        Outcome lg = run("langgraph", fanOutJoin(), "hello");
        assertSameOutcome("扇出汇合", dag, lg);
        assertEquals(Set3.of("a", "b", "in", "out"), dag.nodeStatuses().keySet().stream().collect(Collectors.toSet()));
    }

    @Test
    void conditionRoutesToMatchingBranch_sameResult() {
        Outcome dag = run("dag", conditional(), "请帮我转人工");
        Outcome lg = run("langgraph", conditional(), "请帮我转人工");
        assertSameOutcome("条件分支(命中)", dag, lg);
        assertTrue(dag.nodeStatuses().containsKey("human"));
        assertFalse(dag.nodeStatuses().containsKey("bot"), "命中分支时默认分支不应执行");
    }

    @Test
    void conditionFallsBackToDefaultBranch_sameResult() {
        Outcome dag = run("dag", conditional(), "介绍一下 HashMap");
        Outcome lg = run("langgraph", conditional(), "介绍一下 HashMap");
        assertSameOutcome("条件分支(默认)", dag, lg);
        assertTrue(dag.nodeStatuses().containsKey("bot"));
        assertFalse(dag.nodeStatuses().containsKey("human"));
    }

    @Test
    void failureStopsExecution_sameSemantics() {
        Outcome dag = run("dag", failing(1000), "hello");
        TestNodeExecutors.reset();
        Outcome lg = run("langgraph", failing(1000), "hello");

        assertNull(dag.thrown);
        assertNull(lg.thrown);
        for (Outcome o : List.of(dag, lg)) {
            assertEquals("FAILED", o.response.getStatus(), o.engine + ": 状态");
            assertNotNull(o.response.getErrorMessage(), o.engine + ": 应有错误信息");
            assertTrue(o.response.getErrorMessage().contains("bad"), o.engine + ": 错误信息应指出失败节点 " + o.response.getErrorMessage());
            assertEquals("FAILED", o.nodeStatuses().get("bad"), o.engine + ": 失败节点应出现在节点结果里");
            assertFalse(o.nodeStatuses().containsKey("out"), o.engine + ": 失败节点之后的节点不应继续执行");
            assertNull(o.response.getOutputData(), o.engine + ": 失败时不应有最终输出");
        }
        assertEquals(dag.nodeStatuses(), lg.nodeStatuses());
    }

    @Test
    void lifecycle_executionIdEventsRecordAndSnapshots() {
        for (String engine : List.of("dag", "langgraph")) {
            TestNodeExecutors.reset();
            Outcome o = run(engine, linear(), "hello");
            assertNull(o.thrown, engine);
            ExecutionResponse response = o.response;
            assertNotNull(response.getExecutionId(), engine + ": 响应应带执行 ID");

            ExecutionEvent start = o.events.stream().filter(e -> "WORKFLOW_START".equals(e.getEventType())).findFirst().orElseThrow();
            assertEquals(response.getExecutionId(), ((Number) start.getData()).longValue(),
                    engine + ": WORKFLOW_START 事件必须带上执行 ID（前端据此断线重连/查询进度）");

            List<String> types = o.events.stream().map(ExecutionEvent::getEventType).collect(Collectors.toList());
            assertEquals("WORKFLOW_START", types.get(0), engine);
            assertEquals("WORKFLOW_COMPLETE", types.get(types.size() - 1), engine);
            for (String node : List.of("in", "a", "out")) {
                int s = indexOf(o.events, "NODE_START", node);
                int e = indexOf(o.events, "NODE_SUCCESS", node);
                assertTrue(s >= 0 && e > s, engine + ": 节点 " + node + " 应先 NODE_START 后 NODE_SUCCESS");
            }

            ExecutionRecord record = recordMapper.selectById(response.getExecutionId());
            assertNotNull(record, engine + ": 执行记录应落库");
            assertEquals("SUCCESS", record.getStatus(), engine);

            List<ExecutionSnapshot> snapshots = snapshotMapper.selectByExecutionId(response.getExecutionId());
            assertEquals(List.of("a", "in", "out"),
                    snapshots.stream().map(ExecutionSnapshot::getNodeId).sorted().collect(Collectors.toList()),
                    engine + ": 每个执行过的节点都应有快照（时间线与断点续跑依赖它）");
            assertTrue(snapshots.stream().allMatch(s -> "SUCCESS".equals(s.getStatus())), engine);
        }
    }

    @Test
    void invalidGraphsAreRejectedTheSameWay() {
        Workflow cyclic = new FlowBuilder().input("in").task("a").task("b")
                .edge("in", "a").edge("a", "b").edge("b", "a").build(FLOW_ID, "dag");
        Workflow dangling = new FlowBuilder().input("in").task("a")
                .edge("in", "a").edge("a", "ghost").build(FLOW_ID, "dag");

        for (String engine : List.of("dag", "langgraph")) {
            Outcome c = run(engine, cyclic, "x");
            assertNotNull(c.thrown, engine + ": 有环的工作流必须被拒绝");
            assertTrue(c.thrown.getMessage().contains("循环依赖"), engine + ": " + c.thrown.getMessage());

            Outcome d = run(engine, dangling, "x");
            assertNotNull(d.thrown, engine + ": 引用了不存在节点的连线必须被拒绝");
            assertTrue(d.thrown.getMessage().contains("ghost"), engine + ": " + d.thrown.getMessage());
        }
        assertTrue(recordMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExecutionRecord>()
                .eq(ExecutionRecord::getFlowId, FLOW_ID)).isEmpty(), "被拒绝的工作流不应留下任何执行记录（尤其不能留下 RUNNING 的）");
    }

    @Test
    void failedExecutionOfEitherEngineCanBeResumedToTheSameResult() {
        Outcome clean = run("dag", failing(0), "hello");
        assertEquals("SUCCESS", clean.response.getStatus());

        for (String engine : List.of("dag", "langgraph")) {
            TestNodeExecutors.reset();
            Outcome failed = run(engine, failing(1), "hello");
            assertEquals("FAILED", failed.response.getStatus(), engine);

            ExecutionResponse resumed = dagEngine.resumeExecution(failing(1), failed.response.getExecutionId(),
                    new ResumeExecutionRequest(), null);
            assertEquals("SUCCESS", resumed.getStatus(), engine + ": 续跑应成功");
            assertEquals(JSON.parseObject(clean.response.getOutputData()), JSON.parseObject(resumed.getOutputData()),
                    engine + ": 续跑得到的最终输出应与一次性跑通一致");
        }
    }

    private static int indexOf(List<ExecutionEvent> events, String type, String nodeId) {
        for (int i = 0; i < events.size(); i++) {
            ExecutionEvent e = events.get(i);
            if (type.equals(e.getEventType()) && nodeId.equals(e.getNodeId())) {
                return i;
            }
        }
        return -1;
    }

    /** 小工具：避免在断言里到处写 Set.of 泛型 */
    private static final class Set3 {
        static java.util.Set<String> of(String... values) {
            return new java.util.HashSet<>(List.of(values));
        }
    }
}
