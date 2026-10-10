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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 节点适配器：把工作流节点适配成 LangGraph 的 AsyncNodeAction。
 * 真正执行节点交给 {@link NodeRunner}（与 DAG 引擎同一份实现：快照、事件、错误信息完全一致），
 * 输入解析、条件路由用 {@link ExecutionSupport}（同样与 DAG 引擎共用）。
 *
 * 并行的关键：LangGraph4j 的并行分支是把每个分支节点的 AsyncNodeAction 依次调用，再等所有返回的 Future 完成。
 * 如果动作在调用线程里同步干完活，再返回一个"已完成的 Future"，所谓并行就退化成了串行。
 * 所以并行层里的节点必须把真正的工作丢到节点线程池（与 DAG 引擎共用同一个有界池）里，立刻返回未完成的 Future。
 */
@Slf4j
@Component
public class NodeAdapter {

    @Autowired
    private NodeRunner nodeRunner;

    /** 与 DAG 引擎共用：满了由调用线程自己跑（退化成串行），不会丢任务；测试里不一定存在。 */
    @Autowired(required = false)
    @Qualifier("nodePool")
    private ThreadPoolExecutor nodePool;

    @Value("${paiagent.engine.parallel-enabled:true}")
    private boolean parallelEnabled;

    /**
     * @param soleNodeInLevel 本层只有这一个节点：直接在当前线程执行，也可以直接更新"当前输入"；
     *                        多节点的层里各节点并发执行，"当前输入"由层屏障统一、确定性地更新。
     */
    public AsyncNodeAction<AgentState> adaptNode(WorkflowNode node, LangGraphRunContext ctx, boolean soleNodeInLevel) {
        return (AgentState state) -> {
            if (!soleNodeInLevel && parallelEnabled && nodePool != null) {
                return CompletableFuture.supplyAsync(() -> execute(node, ctx, false), nodePool);
            }
            return CompletableFuture.completedFuture(execute(node, ctx, soleNodeInLevel));
        };
    }

    /** 什么都不做的屏障入口：只用来让 LangGraph 从这里同时扇出到本层所有节点。 */
    public AsyncNodeAction<AgentState> passThrough() {
        return (AgentState state) -> CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
    }

    /**
     * 层屏障（汇合点）：本层所有节点都结束后，按拓扑序取最后一个成功节点的输出作为"当前输入"，
     * 与 DAG 引擎在层结束后统一处理结果的规则一致，不依赖节点谁先谁后完成。
     */
    public AsyncNodeAction<AgentState> levelBarrier(List<WorkflowNode> level, LangGraphRunContext ctx) {
        return (AgentState state) -> {
            Map<String, Object> last = null;
            for (WorkflowNode node : level) {
                Map<String, Object> output = ctx.nodeOutputs.get(node.getId());
                if (output != null) {
                    last = output;
                }
            }
            if (last != null) {
                ctx.currentInput = last;
            }
            return CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
        };
    }

    private Map<String, Object> execute(WorkflowNode node, LangGraphRunContext ctx, boolean soleNodeInLevel) {
        // 条件分支没选中的节点：空跑（DAG 引擎里是"不放进可执行列表"，效果相同：没有快照、没有结果）
        if (ctx.skipped.contains(node.getId())) {
            return Collections.emptyMap();
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
            // 同层的兄弟节点不会因此被打断（与 DAG 引擎一致：同层跑完并落快照，续跑时可直接复用）；
            // 层与层之间的路由函数看到失败后会直接走 END
            ctx.recordFailure(run);
            stateUpdate.put("status", "FAILED");
            stateUpdate.put("errorMessage", "节点 " + node.getId() + " 执行失败: " + run.error.getMessage());
            return stateUpdate;
        }

        ctx.nodeOutputs.put(node.getId(), run.output);
        if (soleNodeInLevel) {
            ctx.currentInput = run.output;
        }
        if ("condition".equals(node.getType())) {
            String selectedBranch = (String) run.rawOutput.get("__selectedBranch__");
            ExecutionSupport.markSkippedNodes(node.getId(), selectedBranch,
                    ctx.indexes.bySource(), ctx.indexes.byTarget(), ctx.skipped);
        }
        return stateUpdate;
    }
}
