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
 * 图构建器：把工作流按"依赖层"构建成 LangGraph StateGraph，同一层的节点用 LangGraph4j 原生的并行分支执行。
 *
 * 为什么不把工作流的连线一对一翻译成 StateGraph 的边：LangGraph4j 的并行分支有三条硬约束
 * （用 1.8.0-beta3 实测）——所有并行分支必须汇合到同一个节点、并行分支里不能有条件边、每条并行分支只能是单个节点。
 * 真实工作流经常违反这些约束（分支里串多个节点、分支里有条件节点、条件的每个出口再扇出）。
 *
 * 所以这里按依赖层来搭图（与 DAG 引擎的分层完全一致），层与层之间用"屏障"隔开：
 *
 *   上一层出口 --条件边(失败→END)--> [fan_L] ==并行边==> 节点1..k ==> [join_L] --条件边--> 下一层入口
 *
 * - 一层只有一个节点：直接放节点，没有屏障；
 * - 一层有多个节点：fan_L 同时连向本层所有节点（并行分支），每个节点只连一条边到同一个 join_L，满足上述三条约束；
 * - 条件分支的"跳过"不用 LangGraph 的条件边表达，而是由节点自己判断（NodeAdapter 里按 skipped 集合直接空跑），
 *   所以条件节点可以放在任意一层、和别的节点并行，不会触发"并行分支里不能有条件边"；
 * - 层与层之间的条件边只做一件事：已经失败就直接走 END。
 * 这样语义与 DAG 引擎一致（EngineContractTest 逐项验证），并且层内真正并行。
 */
@Slf4j
@Component
public class GraphBuilder {

    static final String FAN_PREFIX = "lg_barrier_fan_";
    static final String JOIN_PREFIX = "lg_barrier_join_";

    @Autowired
    private NodeAdapter nodeAdapter;

    public CompiledGraph<AgentState> buildGraph(List<List<WorkflowNode>> levels, LangGraphRunContext ctx) throws GraphStateException {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("工作流没有任何节点");
        }
        int levelCount = levels.size();
        log.info("开始构建 LangGraph: 层数={}", levelCount);

        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        String[] entry = new String[levelCount];
        String[] exit = new String[levelCount];

        for (int l = 0; l < levelCount; l++) {
            List<WorkflowNode> level = levels.get(l);
            if (level.size() == 1) {
                WorkflowNode node = level.get(0);
                graph.addNode(node.getId(), nodeAdapter.adaptNode(node, ctx, true));
                entry[l] = node.getId();
                exit[l] = node.getId();
                continue;
            }
            String fan = FAN_PREFIX + l;
            String join = JOIN_PREFIX + l;
            graph.addNode(fan, nodeAdapter.passThrough());
            graph.addNode(join, nodeAdapter.levelBarrier(level, ctx));
            for (WorkflowNode node : level) {
                graph.addNode(node.getId(), nodeAdapter.adaptNode(node, ctx, false));
                graph.addEdge(fan, node.getId());
                graph.addEdge(node.getId(), join);
            }
            entry[l] = fan;
            exit[l] = join;
        }

        graph.addEdge(StateGraph.START, entry[0]);
        for (int l = 0; l < levelCount; l++) {
            if (l == levelCount - 1) {
                graph.addEdge(exit[l], StateGraph.END);
                continue;
            }
            final String next = entry[l + 1];
            Map<String, String> routes = new LinkedHashMap<>();
            routes.put(next, next);
            routes.put(StateGraph.END, StateGraph.END);
            graph.addConditionalEdges(exit[l],
                    state -> CompletableFuture.completedFuture(ctx.failure != null ? StateGraph.END : next),
                    routes);
        }

        CompiledGraph<AgentState> compiled = graph.compile();
        log.info("LangGraph 构建完成");
        return compiled;
    }
}
