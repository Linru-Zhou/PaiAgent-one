package com.paiagent.engine.support;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 引擎与 LangGraph 引擎共用的执行语义：节点输入怎么从上游输出里解析、条件分支怎么路由、
 * 哪些字段只属于运行时。两套引擎调用的是同一份代码，"结果一致"是靠结构保证的，不是靠两边各写一遍再祈祷一致。
 */
@Slf4j
public final class ExecutionSupport {

    /** 只在运行时传递、不应该写进快照/执行记录/输出的字段 */
    public static final Set<String> RUNTIME_ONLY_KEYS = Set.of("__nodeOutputs__", "__executionId__", "__idempotencyKey__");

    private ExecutionSupport() {
    }

    public record EdgeIndexes(List<WorkflowEdge> edges,
                              Map<String, List<WorkflowEdge>> bySource,
                              Map<String, List<WorkflowEdge>> byTarget) {

        public static EdgeIndexes of(List<WorkflowEdge> edges) {
            Map<String, List<WorkflowEdge>> bySource = new HashMap<>();
            Map<String, List<WorkflowEdge>> byTarget = new HashMap<>();
            for (WorkflowEdge edge : edges) {
                bySource.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
                byTarget.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge);
            }
            return new EdgeIndexes(edges, bySource, byTarget);
        }
    }

    /**
     * 解析节点输入：如果存在已执行的前驱节点输出，按入边顺序合并它们的输出（后面的入边覆盖前面的同名字段）；
     * 没有前驱（起始节点）或前驱都没有输出时，使用兜底输入。
     */
    public static Map<String, Object> resolveNodeInput(WorkflowNode node,
                                                       Map<String, Map<String, Object>> nodeOutputs,
                                                       Map<String, List<WorkflowEdge>> edgesByTarget,
                                                       Map<String, Object> fallbackInput) {
        List<WorkflowEdge> incomingEdges = edgesByTarget.get(node.getId());
        if (incomingEdges == null || incomingEdges.isEmpty()) {
            return fallbackInput;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (WorkflowEdge edge : incomingEdges) {
            Map<String, Object> sourceOutput = nodeOutputs.get(edge.getSource());
            if (sourceOutput != null) {
                resolved.putAll(sourceOutput);
            }
        }
        if (resolved.isEmpty()) {
            return fallbackInput;
        }
        resolved.put("__nodeOutputs__", nodeOutputs);
        return resolved;
    }

    /**
     * 标记条件分支中未选中路径的所有下游节点为跳过状态
     */
    public static void markSkippedNodes(String conditionNodeId,
                                        String selectedBranch,
                                        Map<String, List<WorkflowEdge>> edgesBySource,
                                        Map<String, List<WorkflowEdge>> edgesByTarget,
                                        Set<String> skippedNodes) {
        List<WorkflowEdge> outEdges = edgesBySource.get(conditionNodeId);
        if (outEdges == null || outEdges.isEmpty()) {
            return;
        }

        // 判断出边是否使用了 sourceHandle（分支标识）
        boolean hasHandles = outEdges.stream().anyMatch(e -> e.getSourceHandle() != null);
        if (!hasHandles) {
            return;
        }

        // 收集活跃和非活跃分支的直接目标
        Set<String> activeTargets = new HashSet<>();
        Set<String> inactiveTargets = new HashSet<>();

        for (WorkflowEdge edge : outEdges) {
            String handle = edge.getSourceHandle();
            if (handle != null && handle.equals(selectedBranch)) {
                activeTargets.add(edge.getTarget());
            } else if (handle == null && "default".equals(selectedBranch)) {
                activeTargets.add(edge.getTarget());
            } else {
                inactiveTargets.add(edge.getTarget());
            }
        }

        // 递归标记非活跃分支下游节点
        for (String target : inactiveTargets) {
            if (!activeTargets.contains(target)) {
                markDownstreamSkipped(target, skippedNodes, edgesBySource, edgesByTarget);
            }
        }
    }

    /**
     * 递归标记下游节点为跳过。仅当节点所有入边都来自已跳过节点时才标记。
     */
    private static void markDownstreamSkipped(String nodeId,
                                              Set<String> skippedNodes,
                                              Map<String, List<WorkflowEdge>> edgesBySource,
                                              Map<String, List<WorkflowEdge>> edgesByTarget) {
        if (skippedNodes.contains(nodeId)) {
            return;
        }

        // 检查是否所有入边的源节点都已被跳过（或尚无输出）
        List<WorkflowEdge> incomingEdges = edgesByTarget.get(nodeId);
        if (incomingEdges != null && incomingEdges.size() > 1) {
            for (WorkflowEdge edge : incomingEdges) {
                if (!skippedNodes.contains(edge.getSource())) {
                    // 有活跃路径可达此节点，不跳过
                    return;
                }
            }
        }

        skippedNodes.add(nodeId);
        log.info("标记节点 [{}] 为跳过（条件分支未选中路径）", nodeId);

        // 递归标记下游
        List<WorkflowEdge> outEdges = edgesBySource.get(nodeId);
        if (outEdges != null) {
            for (WorkflowEdge edge : outEdges) {
                markDownstreamSkipped(edge.getTarget(), skippedNodes, edgesBySource, edgesByTarget);
            }
        }
    }

    public static String toJson(Object value) {
        return JSON.toJSONString(stripRuntimeValue(value, new IdentityHashMap<>()));
    }

    public static Map<String, Object> stripRuntimeFields(Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> stripped = (Map<String, Object>) stripRuntimeValue(value, new IdentityHashMap<>());
        return stripped;
    }

    public static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.parseObject(json));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static Object stripRuntimeValue(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (seen.containsKey(value)) {
            return "[Circular]";
        }

        if (value instanceof Map<?, ?> map) {
            seen.put(value, Boolean.TRUE);
            Map<String, Object> stripped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (RUNTIME_ONLY_KEYS.contains(key)) {
                    continue;
                }
                stripped.put(key, stripRuntimeValue(entry.getValue(), seen));
            }
            seen.remove(value);
            return stripped;
        }

        if (value instanceof List<?> list) {
            seen.put(value, Boolean.TRUE);
            List<Object> stripped = new ArrayList<>();
            for (Object item : list) {
                stripped.add(stripRuntimeValue(item, seen));
            }
            seen.remove(value);
            return stripped;
        }

        return value;
    }

    /** 节点对外展示的名字：优先用用户在画布上起的标签，没有才退回节点类型。 */
    /** 取消后写进执行记录的说明；两个引擎共用，让同样的取消在哪个引擎上都是同一句话。 */
    public static String cancelMessage(List<com.paiagent.dto.ExecutionResponse.NodeResult> nodeResults) {
        long done = nodeResults.stream().filter(result -> "SUCCESS".equals(result.getStatus())).count();
        return "执行已取消（已完成 " + done + " 个节点，其余未启动或已中断；可以从断点继续执行）";
    }

    public static String displayName(com.paiagent.engine.model.WorkflowNode node) {
        if (node.getData() != null && node.getData().get("label") instanceof String label && !label.isBlank()) {
            return label;
        }
        return node.getType();
    }

    /** 并发执行的节点会从不同线程回调事件，而 SseEmitter 不是线程安全的，这里统一串行化。 */
    public static java.util.function.Consumer<com.paiagent.dto.ExecutionEvent> synchronizedCallback(
            java.util.function.Consumer<com.paiagent.dto.ExecutionEvent> callback) {
        if (callback == null) {
            return null;
        }
        Object lock = new Object();
        return event -> {
            synchronized (lock) {
                callback.accept(event);
            }
        };
    }
}
