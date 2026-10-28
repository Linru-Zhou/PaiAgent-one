package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.AgentToolRegistry;
import com.paiagent.engine.agent.NativeToolCallback;
import com.paiagent.engine.llm.LLMNodeConfig;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.skill.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * ReAct runtime implemented inside a workflow node.
 *
 * <p>Two ways to let the model ask for a tool, selected by the node's {@code toolCallMode}:
 * <ul>
 *   <li>{@code native} (default): the tools are sent as the provider's own {@code tools} parameter, so the
 *       provider guarantees the structure of a call (tool name + JSON arguments) and the history is kept as real
 *       assistant/tool messages. Spring AI is told <em>not</em> to run the tools itself ({@code proxyToolCalls}),
 *       because this loop owns step limits, tracing, progress events and error observations.</li>
 *   <li>{@code prompt}: the old behaviour for models without tool support — the model is asked to answer with a
 *       JSON decision in plain text, and the code parses it. A reply that cannot be parsed is <b>not</b> silently
 *       taken as the final answer any more: it is recorded as a {@code format_error} step, the model is told what
 *       was wrong on the next step, and after {@value #MAX_CONSECUTIVE_FORMAT_ERRORS} in a row the node fails.</li>
 * </ul>
 * In both modes a bad tool call (unknown tool, arguments that are not valid JSON, the tool throwing) is handed back
 * to the model as an error observation so it can correct itself, instead of failing the whole node.
 */
@Slf4j
@Component
public class ReActAgentNodeExecutor extends AbstractLLMNodeExecutor {

    private static final int DEFAULT_MAX_STEPS = 5;
    private static final int MAX_ALLOWED_STEPS = 20;
    static final int MAX_CONSECUTIVE_FORMAT_ERRORS = 2;

    static final String MODE_NATIVE = "native";
    static final String MODE_PROMPT = "prompt";

    @Autowired
    private AgentToolRegistry toolRegistry;

    @Override
    protected String getNodeType() {
        return "react_agent";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        LLMNodeConfig config = extractConfig(node);
        validateConfig(config);

        String goalPrompt = promptTemplateService.processTemplate(
                config.getPromptTemplate(),
                config.getInputParams(),
                input
        );
        int maxSteps = resolveMaxSteps(node.getData());
        String mode = resolveToolCallMode(node.getData());
        List<AgentTool> availableTools = toolRegistry.getTools(resolveToolNames(node.getData()));
        if (availableTools.isEmpty()) {
            throw new IllegalArgumentException("ReAct Agent 至少需要启用一个工具");
        }

        ChatClient chatClient = chatClientFactory.createClient(
                config.getProvider(),
                config.getApiUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getTemperature()
        );

        Run run = new Run(node, input, progressCallback, availableTools, chatClient, goalPrompt, maxSteps,
                new AgentToolContext(node, input));
        return MODE_PROMPT.equals(mode)
                ? runPromptLoop(run, buildPromptModeSystemPrompt(config, availableTools, node.getData()))
                : runNativeLoop(run, buildNativeSystemPrompt(config, node.getData()));
    }

    // ------------------------------------------------------------------ native function calling

    private Map<String, Object> runNativeLoop(Run run, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(buildNativeUserPrompt(run.goalPrompt, run.input)));

        // 只声明工具，不让 Spring AI 代为执行：步数上限、轨迹、进度事件和错误回传都由本循环负责
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .functionCallbacks(run.tools.stream().map(NativeToolCallback::new).collect(Collectors.toList()))
                .proxyToolCalls(true)
                .build();

        for (int step = 1; step <= run.maxSteps; step++) {
            ChatResponse response = run.chatClient.prompt(new Prompt(new ArrayList<>(messages), options))
                    .call().chatResponse();
            run.tokenUsage.add(response);
            AssistantMessage reply = response.getResult().getOutput();
            String text = reply.getContent() == null ? "" : reply.getContent().trim();

            if (!reply.hasToolCalls()) {
                if (text.isEmpty()) {
                    throw new IllegalStateException("ReAct 第 " + step + " 步：模型既没有调用工具，也没有给出答案");
                }
                Map<String, Object> traceItem = traceItem(step, "final_answer", text);
                traceItem.put("finalAnswer", text);
                run.trace.add(traceItem);
                emitProgress(run, "ReAct 完成", traceItem);
                return buildOutput(text, run.trace, run.tokenUsage, false, null);
            }

            List<AssistantMessage.ToolCall> calls = withCallIds(reply.getToolCalls(), step);
            messages.add(new AssistantMessage(reply.getContent(), Map.of(), calls));

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : calls) {
                Map<String, Object> toolInput;
                Map<String, Object> observation;
                try {
                    toolInput = parseArguments(call.arguments());
                    observation = executeTool(run, call.name(), toolInput);
                } catch (JSONException | IllegalArgumentException badArguments) {
                    toolInput = Map.of("rawArguments", String.valueOf(call.arguments()));
                    observation = errorObservation("工具参数不是合法的 JSON 对象: " + badArguments.getMessage()
                            + "。请按工具的参数定义重新调用");
                }
                Map<String, Object> traceItem = traceItem(step, "tool_call", text);
                traceItem.put("toolName", call.name());
                traceItem.put("toolInput", toolInput);
                traceItem.put("observation", observation);
                traceItem.put("toolCallId", call.id());
                run.trace.add(traceItem);
                emitProgress(run, "ReAct 工具调用: " + call.name(), traceItem);
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), JSON.toJSONString(observation)));
            }
            messages.add(new ToolResponseMessage(responses));
        }
        return maxStepsReached(run);
    }

    /** 个别服务商不返回 tool_call id，但回传工具结果时必须带同一个 id，所以缺失时补一个。 */
    private List<AssistantMessage.ToolCall> withCallIds(List<AssistantMessage.ToolCall> calls, int step) {
        List<AssistantMessage.ToolCall> normalized = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            String id = isBlank(call.id()) ? "call_" + step + "_" + i : call.id();
            normalized.add(new AssistantMessage.ToolCall(id, isBlank(call.type()) ? "function" : call.type(),
                    call.name(), call.arguments()));
        }
        return normalized;
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (isBlank(arguments)) {
            return new LinkedHashMap<>();
        }
        JSONObject json = JSON.parseObject(arguments);
        if (json == null) {
            throw new IllegalArgumentException("参数为空");
        }
        return new LinkedHashMap<>(json);
    }

    private String buildNativeSystemPrompt(LLMNodeConfig config, Map<String, Object> nodeData) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个运行在 PaiAgent 工作流节点里的 Agent。\n");
        sb.append("需要外部信息或要执行操作时，调用提供给你的工具；信息足够时直接用文字给出最终答案，不要再调用工具。\n");
        sb.append("工具返回 error 时，根据错误信息修正参数后重试，或换一种方式完成任务。\n\n");
        appendContext(sb, config, nodeData);
        return sb.toString();
    }

    private String buildNativeUserPrompt(String goalPrompt, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", goalPrompt);
        payload.put("currentInput", input);
        return JSON.toJSONString(payload);
    }

    // ------------------------------------------------------------------ prompted JSON (fallback)

    private Map<String, Object> runPromptLoop(Run run, String systemPrompt) {
        int formatErrors = 0;
        for (int step = 1; step <= run.maxSteps; step++) {
            String userPrompt = buildStepPrompt(run.goalPrompt, run.input, run.trace, step, run.maxSteps);
            ChatResponse response = run.chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ))).call().chatResponse();

            run.tokenUsage.add(response);
            String content = response.getResult().getOutput().getContent();

            AgentDecision decision;
            try {
                decision = parseDecision(content);
            } catch (DecisionFormatException e) {
                formatErrors++;
                log.warn("ReAct 响应无法解析为决策 (连续第 {} 次): node={}, {}", formatErrors, run.node.getId(), e.getMessage());
                Map<String, Object> traceItem = traceItem(step, "format_error", "");
                traceItem.put("error", e.getMessage());
                traceItem.put("rawResponse", abbreviate(content, 500));
                run.trace.add(traceItem);
                emitProgress(run, "ReAct 响应格式错误", traceItem);
                if (formatErrors >= MAX_CONSECUTIVE_FORMAT_ERRORS) {
                    throw new IllegalStateException("ReAct 模型连续 " + formatErrors
                            + " 次没有按约定输出 JSON 决策，已停止: " + e.getMessage()
                            + "（若该模型支持函数调用，可把节点的 toolCallMode 设为 native）");
                }
                continue;
            }
            formatErrors = 0;

            Map<String, Object> traceItem = traceItem(step, decision.action(), decision.reasoningSummary());
            if ("final_answer".equals(decision.action())) {
                traceItem.put("finalAnswer", decision.finalAnswer());
                run.trace.add(traceItem);
                emitProgress(run, "ReAct 完成", traceItem);
                return buildOutput(decision.finalAnswer(), run.trace, run.tokenUsage, false, null);
            }

            Map<String, Object> observation = executeTool(run, decision.toolName(), decision.toolInput());
            traceItem.put("toolName", decision.toolName());
            traceItem.put("toolInput", decision.toolInput());
            traceItem.put("observation", observation);
            run.trace.add(traceItem);
            emitProgress(run, "ReAct 工具调用: " + decision.toolName(), traceItem);
        }
        return maxStepsReached(run);
    }

    private String buildPromptModeSystemPrompt(LLMNodeConfig config, List<AgentTool> tools,
                                               Map<String, Object> nodeData) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个运行在 PaiAgent 工作流节点里的 ReAct Agent。\n");
        sb.append("你必须通过 JSON 决策进行工具调用或给出最终答案，不要输出 Markdown 代码块。\n");
        sb.append("不要暴露完整隐藏推理链，只在 reasoningSummary 字段给一句简短行动理由。\n\n");
        appendContext(sb, config, nodeData);

        sb.append("可用工具:\n");
        for (AgentTool tool : tools) {
            sb.append("- name: ").append(tool.getName()).append("\n");
            sb.append("  description: ").append(tool.getDescription()).append("\n");
            sb.append("  inputSchema: ").append(tool.getInputSchema()).append("\n");
        }

        sb.append("""

                每次只返回下面两种 JSON 之一：
                {"action":"tool_call","reasoningSummary":"为什么需要这个工具","toolName":"工具名","toolInput":{}}
                {"action":"final_answer","reasoningSummary":"为什么可以结束","finalAnswer":"最终答案"}
                """);
        return sb.toString();
    }

    private String buildStepPrompt(String goalPrompt, Map<String, Object> input,
                                   List<Map<String, Object>> trace, int step, int maxSteps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", goalPrompt);
        payload.put("currentInput", input);
        payload.put("previousSteps", trace);
        payload.put("step", step);
        payload.put("maxSteps", maxSteps);
        return JSON.toJSONString(payload);
    }

    /** 解析不了就抛 {@link DecisionFormatException}，由循环记成 format_error 并让模型重试，而不是当成最终答案。 */
    private AgentDecision parseDecision(String content) {
        String cleaned = stripCodeFence(content);
        if (cleaned.isEmpty()) {
            throw new DecisionFormatException("模型返回了空内容");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(cleaned);
        } catch (JSONException e) {
            throw new DecisionFormatException("返回内容不是合法的 JSON（开头是: " + abbreviate(cleaned, 40)
                    + "）。必须只返回一个 JSON 对象，不要附带解释文字");
        }
        if (json == null) {
            throw new DecisionFormatException("返回内容不是 JSON 对象");
        }
        String toolName = firstString(json, "toolName", "tool_name");
        String finalAnswer = firstString(json, "finalAnswer", "final_answer", "answer", "content");
        String action = normalizeAction(json.getString("action"), toolName, finalAnswer);
        String reasoningSummary = defaultString(json.getString("reasoningSummary"));
        Map<String, Object> toolInput;
        try {
            toolInput = toMap(firstValue(json, "toolInput", "tool_input", "arguments"));
        } catch (JSONException | IllegalArgumentException e) {
            throw new DecisionFormatException("toolInput 不是 JSON 对象: " + e.getMessage());
        }

        if ("tool_call".equals(action) && isBlank(toolName)) {
            throw new DecisionFormatException("action 是 tool_call，但缺少 toolName");
        }
        if ("final_answer".equals(action) && finalAnswer == null) {
            throw new DecisionFormatException("action 是 final_answer，但缺少 finalAnswer");
        }
        return new AgentDecision(action, reasoningSummary, toolName, toolInput, defaultString(finalAnswer));
    }

    private String normalizeAction(String action, String toolName, String finalAnswer) {
        if (action == null || action.isBlank()) {
            // 没写 action 时只在意图明确的情况下推断，不再一律当作最终答案
            if (!isBlank(toolName)) {
                return "tool_call";
            }
            if (finalAnswer != null) {
                return "final_answer";
            }
            throw new DecisionFormatException("缺少 action 字段");
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tool", "call_tool", "function_call", "tool_call" -> "tool_call";
            case "final", "answer", "final_answer" -> "final_answer";
            default -> throw new DecisionFormatException("action 只能是 tool_call 或 final_answer，收到: " + action);
        };
    }

    // ------------------------------------------------------------------ shared

    /** 执行一次工具调用。任何问题都变成可恢复的错误观察交还给模型，而不是让整个节点失败。 */
    private Map<String, Object> executeTool(Run run, String toolName, Map<String, Object> toolInput) {
        AgentTool tool = run.tools.stream().filter(candidate -> candidate.getName().equals(toolName))
                .findFirst().orElse(null);
        if (tool == null) {
            String available = run.tools.stream().map(AgentTool::getName).collect(Collectors.joining(", "));
            log.warn("ReAct 调用了未启用的工具: node={}, tool={}", run.node.getId(), toolName);
            return errorObservation("工具 " + toolName + " 不存在或未启用，可用工具: " + available);
        }
        try {
            return tool.execute(toolInput, run.toolContext);
        } catch (Exception toolError) {
            log.warn("ReAct 工具调用失败: node={}, tool={}, error={}", run.node.getId(), toolName, toolError.getMessage());
            return errorObservation(toolError.getMessage());
        }
    }

    private Map<String, Object> errorObservation(String message) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("error", message);
        observation.put("recoverable", true);
        return observation;
    }

    private void appendContext(StringBuilder sb, LLMNodeConfig config, Map<String, Object> nodeData) {
        if (hasText(nodeData.get("knowledgeBaseId"))) {
            sb.append("当前节点已配置知识库。回答项目资料、实现计划、配置字段或节点说明相关问题时，必须先调用 knowledge_retrieve 检索知识库，再基于 observation 回答。\n\n");
        }

        if (config.getSkillName() != null && !config.getSkillName().isBlank()) {
            Optional<Skill> skill = skillRegistry.getSkill(config.getSkillName());
            skill.ifPresent(value -> {
                sb.append("当前节点关联 Skill: ").append(value.getName()).append("\n");
                sb.append(value.getSummary()).append("\n\n");
            });
        }
    }

    private Map<String, Object> traceItem(int step, String action, String reasoningSummary) {
        Map<String, Object> traceItem = new LinkedHashMap<>();
        traceItem.put("step", step);
        traceItem.put("action", action);
        traceItem.put("reasoningSummary", reasoningSummary);
        return traceItem;
    }

    private Map<String, Object> maxStepsReached(Run run) {
        String message = "达到最大 ReAct 步数限制: " + run.maxSteps;
        return buildOutput(message, run.trace, run.tokenUsage, true, message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof JSONObject jsonObject) {
            return new LinkedHashMap<>(jsonObject);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return JSON.parseObject(JSON.toJSONString(value), Map.class);
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private Object firstValue(JSONObject json, String... names) {
        for (String name : names) {
            if (json.containsKey(name)) {
                return json.get(name);
            }
        }
        return null;
    }

    private String firstString(JSONObject json, String... names) {
        Object value = firstValue(json, names);
        return value == null ? null : value.toString();
    }

    private Map<String, Object> buildOutput(String finalAnswer, List<Map<String, Object>> trace,
                                            TokenUsage tokenUsage, boolean maxStepsReached,
                                            String stopReason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", finalAnswer);
        output.put("finalAnswer", finalAnswer);
        output.put("toolTrace", trace);
        output.put("steps", trace.size());
        output.put("maxStepsReached", maxStepsReached);
        if (stopReason != null) {
            output.put("stopReason", stopReason);
        }
        output.put("inputTokens", tokenUsage.inputTokens);
        output.put("outputTokens", tokenUsage.outputTokens);
        output.put("totalTokens", tokenUsage.totalTokens);
        output.put("tokens", tokenUsage.totalTokens);
        return output;
    }

    private void emitProgress(Run run, String message, Map<String, Object> data) {
        if (run.progressCallback != null) {
            run.progressCallback.accept(ExecutionEvent.nodeProgress(run.node.getId(), run.node.getType(), message, data));
        }
    }

    private int resolveMaxSteps(Map<String, Object> data) {
        Object value = data == null ? null : data.get("maxSteps");
        if (value == null) {
            return DEFAULT_MAX_STEPS;
        }

        int maxSteps = value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(value.toString());
        return Math.max(1, Math.min(MAX_ALLOWED_STEPS, maxSteps));
    }

    /** 没配置时用 native；写错了直接报错，不悄悄换成另一种行为。 */
    static String resolveToolCallMode(Map<String, Object> data) {
        Object value = data == null ? null : data.get("toolCallMode");
        if (value == null || value.toString().isBlank()) {
            return MODE_NATIVE;
        }
        String mode = value.toString().trim().toLowerCase(Locale.ROOT);
        if (!MODE_NATIVE.equals(mode) && !MODE_PROMPT.equals(mode)) {
            throw new IllegalArgumentException("toolCallMode 只能是 native 或 prompt，收到: " + value);
        }
        return mode;
    }

    private List<String> resolveToolNames(Map<String, Object> data) {
        Object value = data == null ? null : data.get("tools");
        List<String> toolNames;
        if (value == null) {
            toolNames = new ArrayList<>();
        } else if (value instanceof List<?> list) {
            toolNames = list.stream()
                    .map(Object::toString)
                    .filter(toolName -> !toolName.isBlank())
                    .collect(Collectors.toList());
        } else {
            toolNames = List.of(value.toString().split(",")).stream()
                    .map(String::trim)
                    .filter(toolName -> !toolName.isBlank())
                    .collect(Collectors.toList());
        }

        if (hasText(data == null ? null : data.get("knowledgeBaseId")) && !toolNames.contains("knowledge_retrieve")) {
            toolNames = new ArrayList<>(toolNames);
            toolNames.add("knowledge_retrieve");
        }
        if (toolNames.contains("web_search") && !toolNames.contains("web_fetch")) {
            List<String> expanded = new ArrayList<>(toolNames);
            expanded.add("web_fetch");
            return expanded;
        }
        return toolNames;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private void validateConfig(LLMNodeConfig config) {
        if (isBlank(config.getProvider())) {
            throw new IllegalArgumentException("ReAct Agent 缺少有效的提供商配置");
        }
        if (isBlank(config.getApiUrl()) || isBlank(config.getApiKey()) || isBlank(config.getModel())) {
            throw new IllegalArgumentException("ReAct Agent 缺少有效的模型配置，请检查全局配置或节点配置");
        }
        if (isBlank(config.getPromptTemplate())) {
            throw new IllegalArgumentException("ReAct Agent 缺少任务提示词");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /** 一次节点执行里循环要用到的所有东西，省得在方法之间传一长串参数。 */
    private static final class Run {
        final WorkflowNode node;
        final Map<String, Object> input;
        final Consumer<ExecutionEvent> progressCallback;
        final List<AgentTool> tools;
        final ChatClient chatClient;
        final String goalPrompt;
        final int maxSteps;
        final AgentToolContext toolContext;
        final List<Map<String, Object>> trace = new ArrayList<>();
        final TokenUsage tokenUsage = new TokenUsage();

        Run(WorkflowNode node, Map<String, Object> input, Consumer<ExecutionEvent> progressCallback,
            List<AgentTool> tools, ChatClient chatClient, String goalPrompt, int maxSteps,
            AgentToolContext toolContext) {
            this.node = node;
            this.input = input;
            this.progressCallback = progressCallback;
            this.tools = tools;
            this.chatClient = chatClient;
            this.goalPrompt = goalPrompt;
            this.maxSteps = maxSteps;
            this.toolContext = toolContext;
        }
    }

    private record AgentDecision(
            String action,
            String reasoningSummary,
            String toolName,
            Map<String, Object> toolInput,
            String finalAnswer
    ) {
    }

    /** prompt 模式下模型的回复不符合约定格式。 */
    private static class DecisionFormatException extends RuntimeException {
        DecisionFormatException(String message) {
            super(message);
        }
    }

    private static class TokenUsage {
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;

        void add(ChatResponse response) {
            if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
                return;
            }

            var usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                inputTokens += usage.getPromptTokens().intValue();
            }
            if (usage.getGenerationTokens() != null) {
                outputTokens += usage.getGenerationTokens().intValue();
            }
            if (usage.getTotalTokens() != null) {
                totalTokens += usage.getTotalTokens().intValue();
            } else {
                totalTokens = inputTokens + outputTokens;
            }
        }
    }
}
