package com.paiagent.engine.langgraph;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.NodeRunner.NodeRun;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.support.ExecutionSupport.EdgeIndexes;
import com.paiagent.entity.ExecutionRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一次 LangGraph 执行的运行上下文。节点输出、已跳过节点、失败信息都放在这里（由调用方独占、与 DAG 引擎同构），
 * 而不是塞进 LangGraph 的状态 Map 里互相覆盖：LangGraph 状态只承载运行元信息（状态、当前节点等）。
 */
public class LangGraphRunContext {

    public final ExecutionRecord record;
    public final Long flowId;
    public final List<WorkflowNode> sortedNodes;
    public final Map<String, Integer> sortedIndex = new HashMap<>();
    public final EdgeIndexes indexes;
    public final Consumer<ExecutionEvent> eventCallback;

    public final Map<String, Map<String, Object>> nodeOutputs = new ConcurrentHashMap<>();
    public final Set<String> skipped = Collections.synchronizedSet(new HashSet<>());
    public final List<ExecutionResponse.NodeResult> nodeResults = Collections.synchronizedList(new ArrayList<>());

    public volatile Map<String, Object> currentInput;
    public volatile NodeRun failure;

    /**
     * 并行的节点可能同时失败：与 DAG 引擎一致，以拓扑序里最靠前的失败节点作为本次执行的失败原因，
     * 这样同样的工作流在哪个引擎上失败，报出来的节点都是同一个。
     */
    public synchronized void recordFailure(NodeRun run) {
        NodeRun current = failure;
        if (current == null
                || sortedIndex.getOrDefault(run.node.getId(), Integer.MAX_VALUE)
                < sortedIndex.getOrDefault(current.node.getId(), Integer.MAX_VALUE)) {
            failure = run;
        }
    }

    public LangGraphRunContext(ExecutionRecord record, Long flowId, List<WorkflowNode> sortedNodes,
                               EdgeIndexes indexes, Consumer<ExecutionEvent> eventCallback, String inputData) {
        this.record = record;
        this.flowId = flowId;
        this.sortedNodes = sortedNodes;
        this.indexes = indexes;
        this.eventCallback = eventCallback;
        for (int i = 0; i < sortedNodes.size(); i++) {
            sortedIndex.put(sortedNodes.get(i).getId(), i);
        }
        Map<String, Object> initial = new HashMap<>();
        initial.put("input", inputData);
        this.currentInput = initial;
    }
}
