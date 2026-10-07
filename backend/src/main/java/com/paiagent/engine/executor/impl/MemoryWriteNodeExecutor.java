package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MemoryWriteNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgentMemoryService memoryService;

    public MemoryWriteNodeExecutor(AgentPlanConfigResolver configResolver,
                                   VolcengineAgentPlanClient agentPlanClient,
                                   AgentMemoryService memoryService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.memoryService = memoryService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        String content = textValue(node, input, "content", "output");
        if (content == null) {
            throw new IllegalArgumentException("写入记忆节点缺少 content");
        }

        ResolvedAgentPlanConfig config = configResolver.resolve(node, "memory");
        List<Double> embedding = List.of();
        if (config.apiUrl() != null && config.apiKey() != null && config.model() != null) {
            embedding = agentPlanClient.createEmbedding(config, content);
        }

        return memoryService.write(
                content,
                stringData(node, "memoryType", "fact"),
                stringData(node, "scope", "workflow"),
                memoryService.splitTags(stringData(node, "tags", "")),
                node.getId(),
                embedding,
                config.model(),
                idempotencyKey(input)
        );
    }

    @Override
    public String getSupportedNodeType() {
        return "memory_write";
    }
}
