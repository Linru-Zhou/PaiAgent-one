package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

abstract class AbstractAgentPlanNodeExecutor implements NodeExecutor {

    protected String textValue(WorkflowNode node, Map<String, Object> input, String field, String fallbackField) {
        Object nodeValue = node.getData().get(field);
        if (StringUtils.hasText(asText(nodeValue))) {
            return asText(nodeValue);
        }
        Object inputValue = input.get(field);
        if (StringUtils.hasText(asText(inputValue))) {
            return asText(inputValue);
        }
        if (StringUtils.hasText(fallbackField)) {
            Object fallbackValue = input.get(fallbackField);
            if (StringUtils.hasText(asText(fallbackValue))) {
                return asText(fallbackValue);
            }
        }
        Object output = input.get("output");
        return StringUtils.hasText(asText(output)) ? asText(output) : null;
    }

    @SuppressWarnings("unchecked")
    protected String configuredTextInput(WorkflowNode node, Map<String, Object> input, String paramName) {
        Object paramsValue = node.getData().get("inputParams");
        if (!(paramsValue instanceof List<?> inputParams)) {
            return null;
        }

        for (Object item : inputParams) {
            if (!(item instanceof Map<?, ?> rawParam)) {
                continue;
            }
            Map<String, Object> param = (Map<String, Object>) rawParam;
            if (!paramName.equals(asText(param.get("name")))) {
                continue;
            }

            String type = asText(param.get("type"));
            if ("input".equals(type)) {
                return asText(param.get("value"));
            }
            if ("reference".equals(type)) {
                String referenceNode = asText(param.get("referenceNode"));
                String referenceKey = extractReferenceKey(referenceNode);
                if (StringUtils.hasText(referenceKey)) {
                    String value = asText(input.get(referenceKey));
                    if (StringUtils.hasText(value)) {
                        return value;
                    }
                    if ("user_input".equals(referenceKey)) {
                        return asText(input.get("input"));
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected void applyOutputParams(WorkflowNode node, Map<String, Object> output) {
        Object paramsValue = node.getData().get("outputParams");
        if (!(paramsValue instanceof List<?> outputParams)) {
            return;
        }
        for (Object item : outputParams) {
            if (!(item instanceof Map<?, ?> rawParam)) {
                continue;
            }
            Map<String, Object> param = (Map<String, Object>) rawParam;
            String name = asText(param.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String sourceField = asText(param.get("value"));
            Object value = StringUtils.hasText(sourceField) ? output.get(sourceField) : output.get(name);
            if (value != null) {
                output.put(name, value);
            }
        }
    }

    private String extractReferenceKey(String referenceNode) {
        if (!StringUtils.hasText(referenceNode)) {
            return null;
        }
        String[] parts = referenceNode.split("\\.");
        return parts.length == 0 ? referenceNode : parts[parts.length - 1];
    }

    /** 引擎注入的幂等键（executionId:nodeId），没有执行上下文（比如单测、ReAct 工具调用）时为 null。 */
    protected String idempotencyKey(Map<String, Object> input) {
        Object key = input == null ? null : input.get("__idempotencyKey__");
        return key == null ? null : String.valueOf(key);
    }

    protected String stringData(WorkflowNode node, String field, String defaultValue) {
        String value = asText(node.getData().get(field));
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    protected int intData(WorkflowNode node, String field, int defaultValue) {
        Object value = node.getData().get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? Integer.parseInt(text) : defaultValue;
    }

    protected double doubleData(WorkflowNode node, String field, double defaultValue) {
        Object value = node.getData().get(field);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? Double.parseDouble(text) : defaultValue;
    }

    protected String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
