package com.paiagent.engine.langgraph;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.ExecutionRecorder;
import com.paiagent.engine.WorkflowConfigParser;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.langgraph.builder.GraphBuilder;
import com.paiagent.engine.langgraph.state.StateManager;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.support.ExecutionSupport.EdgeIndexes;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static com.paiagent.engine.support.ExecutionSupport.stripRuntimeFields;
import static com.paiagent.engine.support.ExecutionSupport.toJson;

/**
 * LangGraph 工作流引擎：用 LangGraph4j 的 StateGraph 作为运行时，节点按拓扑序串行执行。
 *
 * 与 DAG 引擎共用同一套执行语义（见 EngineContractTest 的契约测试）：
 * 同样的图校验（有环/悬空连线直接拒绝，不留执行记录）、执行一开始就有 RUNNING 记录和执行 ID、
 * 每个节点都有快照和心跳、条件分支只走命中的路径、节点失败立即中止并报告失败节点。
 * 区别只有一点：LangGraph 引擎串行执行，不做同层并发。
 */
@Slf4j
@Service
public class LangGraphWorkflowEngine implements WorkflowExecutor {

    @Autowired
    private GraphBuilder graphBuilder;

    @Autowired
    private StateManager stateManager;

    @Autowired
    private ExecutionRecorder recorder;

    @Autowired
    private WorkflowConfigParser workflowConfigParser;

    @Autowired
    private DAGParser dagParser;

    @Override
    public ExecutionResponse execute(Workflow workflow, String inputData) {
        return executeWithCallback(workflow, inputData, null);
    }

    @Override
    public ExecutionResponse executeWithCallback(Workflow workflow, String inputData, Consumer<ExecutionEvent> eventCallback) {
        long startTime = System.currentTimeMillis();
        log.info("开始执行工作流 [{}] - 使用 LangGraph 引擎", workflow.getName());

        // 与 DAG 引擎一致：先校验图，非法（有环/连线引用不存在的节点）直接抛出，此时还没有创建执行记录
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        List<WorkflowNode> sortedNodes = dagParser.parse(config);
        List<WorkflowEdge> edges = config.getEdges() != null ? config.getEdges() : new ArrayList<>();
        EdgeIndexes indexes = EdgeIndexes.of(edges);

        ExecutionRecord record = recorder.start(workflow.getId(), inputData);
        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
        }

        LangGraphRunContext ctx = new LangGraphRunContext(record, workflow.getId(), sortedNodes, indexes, eventCallback, inputData);
        String status = "SUCCESS";
        String errorMessage = null;
        String outputData = null;

        try {
            CompiledGraph<AgentState> graph = graphBuilder.buildGraph(sortedNodes, ctx);
            graph.invoke(stateManager.initializeState(inputData));

            if (ctx.failure != null) {
                status = "FAILED";
                errorMessage = "节点 " + ctx.failure.node.getId() + " 执行失败: " + ctx.failure.error.getMessage();
            } else {
                outputData = toJson(stripRuntimeFields(ctx.currentInput));
            }
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            log.error("LangGraph 执行失败", cause);
            status = "FAILED";
            errorMessage = cause.getMessage();
        }

        List<ExecutionResponse.NodeResult> nodeResults = new ArrayList<>(ctx.nodeResults);
        nodeResults.sort(Comparator.comparingInt(r -> ctx.sortedIndex.getOrDefault(r.getNodeId(), Integer.MAX_VALUE)));

        int duration = (int) (System.currentTimeMillis() - startTime);
        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowComplete(status, ctx.currentInput, duration));
        }
        recorder.finish(record, workflow.getId(), inputData, status, outputData, toJson(nodeResults), errorMessage, duration);

        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(status);
        response.setInputData(ExecutionRecorder.buildInputDataJson(inputData));
        response.setNodeResults(nodeResults);
        response.setOutputData(outputData);
        response.setDuration(duration);
        response.setErrorMessage(errorMessage);
        log.info("工作流执行完成: status={}, duration={}ms", status, duration);
        return response;
    }

    @Override
    public String getEngineType() {
        return "langgraph";
    }
}
