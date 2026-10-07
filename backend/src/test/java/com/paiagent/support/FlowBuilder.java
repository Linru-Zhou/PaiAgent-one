package com.paiagent.support;

import com.alibaba.fastjson2.JSON;
import com.paiagent.entity.Workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用工作流构造器，生成与前端保存格式一致的 flowData。
 */
public class FlowBuilder {

    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<Map<String, Object>> edges = new ArrayList<>();

    public FlowBuilder input(String id) {
        return node(id, "input", Map.of());
    }

    public FlowBuilder output(String id) {
        return node(id, "output", Map.of());
    }

    public FlowBuilder task(String id) {
        return task(id, 0, 0);
    }

    public FlowBuilder task(String id, long sleepMs, int failTimes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sleepMs", sleepMs);
        data.put("failTimes", failTimes);
        return node(id, "test_task", data);
    }

    public FlowBuilder condition(String id, String conditionId, String field, String operator, String value) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", conditionId);
        rule.put("field", field);
        rule.put("operator", operator);
        rule.put("value", value);
        return node(id, "condition", Map.of("conditions", List.of(rule)));
    }

    public FlowBuilder node(String id, String type, Map<String, Object> extraData) {
        Map<String, Object> data = new LinkedHashMap<>(extraData);
        data.put("type", type);
        data.put("label", id);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "workflow");
        node.put("data", data);
        nodes.add(node);
        return this;
    }

    public FlowBuilder edge(String source, String target) {
        return edge(source, target, null);
    }

    public FlowBuilder edge(String source, String target, String sourceHandle) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "e-" + source + "-" + target);
        edge.put("source", source);
        edge.put("target", target);
        if (sourceHandle != null) {
            edge.put("sourceHandle", sourceHandle);
        }
        edges.add(edge);
        return this;
    }

    public String flowData() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", nodes);
        root.put("edges", edges);
        return JSON.toJSONString(root);
    }

    public Workflow build(long flowId, String engineType) {
        Workflow workflow = new Workflow();
        workflow.setId(flowId);
        workflow.setName("test-flow-" + flowId);
        workflow.setEngineType(engineType);
        workflow.setFlowData(flowData());
        return workflow;
    }
}
