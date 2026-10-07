package com.paiagent.engine.langgraph.adapter;

import com.paiagent.engine.NodeRunner;
import com.paiagent.engine.NodeRunner.NodeRun;
import com.paiagent.engine.langgraph.LangGraphRunContext;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.support.ExecutionSupport;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点适配器：把工作流节点适配成 LangGraph 的 AsyncNodeAction。
 * 真正执行节点交给 {@link NodeRunner}（与 DAG 引擎同一份实现：快照、事件、错误信息完全一致），
 * 输入解析、条件路由用 {@link ExecutionSupport}（同样与 DAG 引擎共用）。
 * 节点失败时不再吞掉异常继续往下跑：记录失败后，后续的路由函数会直接把流程导向 END。
 */
@Slf4j
@Component
public class NodeAdapter {

    @Autowired
    private NodeRunner nodeRunner;

    public AsyncNodeAction<AgentState> adaptNode(WorkflowNode node, LangGraphRunContext ctx) {
        return (AgentState state) -> {
            if (ctx.failure != null || ctx.skipped.contains(node.getId())) {
                return CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
            }

            Map<String, Object> input = new LinkedHashMap<>(ExecutionSupport.resolveNodeInput(
                    node,
                    Collections.unmodifiableMap(new HashMap<>(ctx.nodeOutputs)),
                    ctx.indexes.byTarget(),
                    Collections.unmodifiableMap(new HashMap<>(ctx.currentInput))));
            // 运行时字段：执行上下文与幂等键（不会被持久化）
            input.put("__executionId__", ctx.record.getId());
            input.put("__idempotencyKey__", ctx.record.getId() + ":" + node.getId());

            NodeRun run = nodeRunner.run(ctx.record, ctx.flowId, node, input,
                    ctx.sortedIndex.get(node.getId()), ctx.eventCallback);
            ctx.nodeResults.add(run.nodeResult);

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put("currentNodeId", node.getId());
            if (run.error != null) {
                ctx.failure = run;
                stateUpdate.put("status", "FAILED");
                stateUpdate.put("errorMessage", "节点 " + node.getId() + " 执行失败: " + run.error.getMessage());
                return CompletableFuture.completedFuture(stateUpdate);
            }

            ctx.nodeOutputs.put(node.getId(), run.output);
            ctx.currentInput = run.output;
            if ("condition".equals(node.getType())) {
                String selectedBranch = (String) run.rawOutput.get("__selectedBranch__");
                ExecutionSupport.markSkippedNodes(node.getId(), selectedBranch,
                        ctx.indexes.bySource(), ctx.indexes.byTarget(), ctx.skipped);
            }
            stateUpdate.put("currentInput", run.output);
            return CompletableFuture.completedFuture(stateUpdate);
        };
    }
}
