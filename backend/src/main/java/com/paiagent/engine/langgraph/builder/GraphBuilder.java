package com.paiagent.engine.langgraph.builder;

import com.paiagent.engine.langgraph.LangGraphRunContext;
import com.paiagent.engine.langgraph.adapter.NodeAdapter;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 图构建器：把工作流构建成 LangGraph StateGraph。
 *
 * 为什么不把工作流的连线一对一翻译成 StateGraph 的边：LangGraph4j 1.8.0-beta3 不支持同一节点的多条普通出边
 * （addEdge 第二条同源边直接抛 "edge with id: x already exist!"），所以任何带扇出或条件分支两个出口的工作流都建不出图。
 * 这里改为：节点按拓扑顺序注册，START 连第一个节点，每个节点挂一条条件边，
 * 路由函数在运行时给出"拓扑序里下一个没被条件分支跳过的节点"（失败时给 END）。
 * 这样任意 DAG 都能表达，执行顺序和语义与 DAG 引擎的串行模式一致（LangGraph 引擎本身不并发）。
 */
@Slf4j
@Component
public class GraphBuilder {

    @Autowired
    private NodeAdapter nodeAdapter;

    public CompiledGraph<AgentState> buildGraph(List<WorkflowNode> sortedNodes, LangGraphRunContext ctx) throws GraphStateException {
        if (sortedNodes == null || sortedNodes.isEmpty()) {
            throw new IllegalArgumentException("工作流没有任何节点");
        }
        log.info("开始构建 LangGraph: 节点数={}", sortedNodes.size());

        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        for (WorkflowNode node : sortedNodes) {
            graph.addNode(node.getId(), nodeAdapter.adaptNode(node, ctx));
        }

        graph.addEdge(StateGraph.START, sortedNodes.get(0).getId());
        for (int i = 0; i < sortedNodes.size(); i++) {
            String nodeId = sortedNodes.get(i).getId();
            if (i == sortedNodes.size() - 1) {
                graph.addEdge(nodeId, StateGraph.END);
                continue;
            }
            Map<String, String> routes = new LinkedHashMap<>();
            for (int j = i + 1; j < sortedNodes.size(); j++) {
                routes.put(sortedNodes.get(j).getId(), sortedNodes.get(j).getId());
            }
            routes.put(StateGraph.END, StateGraph.END);
            final int current = i;
            graph.addConditionalEdges(nodeId,
                    state -> CompletableFuture.completedFuture(nextRunnable(sortedNodes, ctx, current)),
                    routes);
        }

        CompiledGraph<AgentState> compiled = graph.compile();
        log.info("LangGraph 构建完成");
        return compiled;
    }

    /** 拓扑序里第 current 个节点之后，下一个没被跳过的节点；已经失败则直接结束。 */
    private String nextRunnable(List<WorkflowNode> sortedNodes, LangGraphRunContext ctx, int current) {
        if (ctx.failure != null) {
            return StateGraph.END;
        }
        for (int j = current + 1; j < sortedNodes.size(); j++) {
            String id = sortedNodes.get(j).getId();
            if (!ctx.skipped.contains(id)) {
                return id;
            }
        }
        return StateGraph.END;
    }
}
