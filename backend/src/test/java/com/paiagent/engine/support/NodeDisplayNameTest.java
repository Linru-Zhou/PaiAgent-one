package com.paiagent.engine.support;

import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 节点对外展示名：优先用画布上的标签，缺失/空白才退回类型（否则一个工作流里三个 deepseek 节点无法区分）。 */
class NodeDisplayNameTest {

    private static WorkflowNode node(String type, Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId("n1");
        node.setType(type);
        node.setData(data);
        return node;
    }

    @Test
    void usesLabelWhenPresent() {
        assertEquals("并行LLM-2", ExecutionSupport.displayName(node("deepseek", Map.of("label", "并行LLM-2"))));
    }

    @Test
    void fallsBackToTypeWhenLabelMissingBlankOrNotAString() {
        assertEquals("deepseek", ExecutionSupport.displayName(node("deepseek", null)));
        assertEquals("deepseek", ExecutionSupport.displayName(node("deepseek", new HashMap<>())));
        assertEquals("deepseek", ExecutionSupport.displayName(node("deepseek", Map.of("label", "   "))));
        assertEquals("deepseek", ExecutionSupport.displayName(node("deepseek", Map.of("label", 42))));
    }
}
