package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.support.PaiAgentSpringTest;
import com.paiagent.support.ScriptedOpenAiServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReAct 节点：原生函数调用（默认）与提示词 JSON 两种模式。对端用按剧本回复的本地假服务，
 * 直接检查“客户端发出了什么请求”，而不只看最终答案。
 */
@PaiAgentSpringTest
class ReActAgentNodeExecutorTest {

    @Autowired
    private List<NodeExecutor> executors;

    private NodeExecutor reAct() {
        return executors.stream().filter(e -> "react_agent".equals(e.getSupportedNodeType()))
                .findFirst().orElseThrow();
    }

    private WorkflowNode node(String baseUrl, String mode, int maxSteps) {
        WorkflowNode node = new WorkflowNode();
        node.setId("react-1");
        node.setType("react_agent");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "deepseek");
        data.put("apiUrl", baseUrl);
        data.put("apiKey", "sk-test");
        data.put("model", "fake");
        data.put("prompt", "任务: {{q}}");
        data.put("inputParams", List.of(Map.of("name", "q", "type", "input", "value", "查一下当前输入")));
        data.put("tools", List.of("read_current_input"));
        data.put("maxSteps", maxSteps);
        if (mode != null) {
            data.put("toolCallMode", mode);
        }
        node.setData(data);
        return node;
    }

    private Map<String, Object> run(ScriptedOpenAiServer server, String mode, int maxSteps) throws Exception {
        return reAct().execute(node(server.baseUrl(), mode, maxSteps), new LinkedHashMap<>(Map.of("input", "你好")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trace(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("toolTrace");
    }

    private static JSONObject messageWithRole(JSONObject request, String role) {
        for (Object item : request.getJSONArray("messages")) {
            JSONObject message = (JSONObject) item;
            if (role.equals(message.getString("role"))) {
                return message;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ native

    @Test
    void native_declaresToolsAsTheProvidersToolsParameter_andFeedsResultsBackAsToolMessages() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyToolCall("call_abc", "read_current_input", "{}")
                .replyText("当前输入是你好")) {
            Map<String, Object> output = run(server, null, 5);

            assertEquals("当前输入是你好", output.get("output"));
            assertEquals(false, output.get("maxStepsReached"));
            assertEquals(2, server.requestCount());

            // 第一次请求：工具走 tools 参数，而不是写在提示词里让模型自己拼 JSON
            JSONObject first = server.request(0);
            JSONArray tools = first.getJSONArray("tools");
            assertNotNull(tools, "native 模式必须把工具放进请求的 tools 参数");
            JSONObject function = tools.getJSONObject(0).getJSONObject("function");
            assertEquals("read_current_input", function.getString("name"));
            assertEquals("object", function.getJSONObject("parameters").getString("type"));
            assertFalse(messageWithRole(first, "system").getString("content").contains("reasoningSummary"),
                    "native 模式的系统提示里不应再要求输出 JSON 决策");

            // 第二次请求：历史是真正的 assistant(tool_calls) + tool 消息，tool_call_id 对得上
            JSONObject second = server.request(1);
            JSONObject assistant = messageWithRole(second, "assistant");
            assertEquals("call_abc", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
            JSONObject toolMessage = messageWithRole(second, "tool");
            assertEquals("call_abc", toolMessage.getString("tool_call_id"));
            assertTrue(toolMessage.getString("content").contains("你好"),
                    "工具是在本地执行的，结果里应有当前输入: " + toolMessage.getString("content"));

            List<Map<String, Object>> trace = trace(output);
            assertEquals(2, trace.size());
            assertEquals("tool_call", trace.get(0).get("action"));
            assertEquals("read_current_input", trace.get(0).get("toolName"));
            assertEquals("final_answer", trace.get(1).get("action"));
        }
    }

    @Test
    void native_argumentsThatAreNotJson_areReportedBackToTheModel_notSwallowed() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyToolCall("call_1", "read_current_input", "{not json")
                .replyText("好的")) {
            Map<String, Object> output = run(server, "native", 5);

            assertEquals("好的", output.get("output"));
            Map<String, Object> observation = castMap(trace(output).get(0).get("observation"));
            assertTrue(String.valueOf(observation.get("error")).contains("不是合法的 JSON"), observation.toString());
            assertEquals(true, observation.get("recoverable"));
            String toolContent = messageWithRole(server.request(1), "tool").getString("content");
            assertTrue(toolContent.contains("不是合法的 JSON"), "模型必须能看到错误才有机会改正: " + toolContent);
        }
    }

    @Test
    void native_unknownTool_isAnErrorObservationListingTheAvailableTools() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyToolCall("call_1", "no_such_tool", "{}")
                .replyText("放弃")) {
            Map<String, Object> output = run(server, "native", 5);

            Map<String, Object> observation = castMap(trace(output).get(0).get("observation"));
            String error = String.valueOf(observation.get("error"));
            assertTrue(error.contains("no_such_tool") && error.contains("read_current_input"), error);
            assertEquals("放弃", output.get("output"));
        }
    }

    @Test
    void native_emptyReply_failsLoudlyInsteadOfReturningAnEmptyAnswer() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer().replyNothing()) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(server, "native", 5));
            assertTrue(error.getMessage().contains("既没有调用工具，也没有给出答案"), error.getMessage());
        }
    }

    @Test
    void native_stopsAtMaxSteps_andSaysSo() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyToolCall("call_1", "read_current_input", "{}")) {
            Map<String, Object> output = run(server, "native", 3);

            assertEquals(true, output.get("maxStepsReached"));
            assertEquals(3, server.requestCount(), "模型一直调用工具时，最多只发 maxSteps 次请求");
        }
    }

    // ------------------------------------------------------------------ prompt (fallback)

    @Test
    void prompt_doesNotSendToolsParameter_andStillWorksWithJsonDecisions() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyText("{\"action\":\"tool_call\",\"reasoningSummary\":\"看输入\",\"toolName\":\"read_current_input\",\"toolInput\":{}}")
                .replyText("{\"action\":\"final_answer\",\"reasoningSummary\":\"够了\",\"finalAnswer\":\"答案A\"}")) {
            Map<String, Object> output = run(server, "prompt", 5);

            assertEquals("答案A", output.get("output"));
            assertNull(server.request(0).get("tools"), "prompt 模式不应使用 tools 参数");
            assertTrue(messageWithRole(server.request(0), "system").getString("content").contains("reasoningSummary"));
        }
    }

    @Test
    void prompt_plainTextReply_isAFormatError_notSilentlyTheFinalAnswer() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyText("我觉得答案是 42")
                .replyText("{\"action\":\"final_answer\",\"reasoningSummary\":\"改正\",\"finalAnswer\":\"42\"}")) {
            Map<String, Object> output = run(server, "prompt", 5);

            assertEquals("42", output.get("output"), "最终答案应来自合法的第二次回复，而不是第一次的整段文字");
            List<Map<String, Object>> trace = trace(output);
            assertEquals("format_error", trace.get(0).get("action"));
            assertTrue(String.valueOf(trace.get(0).get("error")).contains("不是合法的 JSON"));
            String secondUserPrompt = messageWithRole(server.request(1), "user").getString("content");
            assertTrue(secondUserPrompt.contains("format_error"), "模型下一步必须看得到自己上一步错在哪");
        }
    }

    @Test
    void prompt_repeatedFormatErrors_failTheNode() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer().replyText("还是纯文字")) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(server, "prompt", 5));

            assertTrue(error.getMessage().contains("连续 2 次"), error.getMessage());
            assertEquals(2, server.requestCount(), "连续两次不合格就停，不把剩余步数浪费完");
        }
    }

    @Test
    void prompt_unknownAction_isAFormatError() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyText("{\"action\":\"dance\"}")
                .replyText("{\"action\":\"final_answer\",\"finalAnswer\":\"ok\"}")) {
            Map<String, Object> output = run(server, "prompt", 5);

            assertEquals("format_error", trace(output).get(0).get("action"));
            assertTrue(String.valueOf(trace(output).get(0).get("error")).contains("dance"));
            assertEquals("ok", output.get("output"));
        }
    }

    @Test
    void prompt_unknownTool_isAnErrorObservation_notANodeFailure() throws Exception {
        try (ScriptedOpenAiServer server = new ScriptedOpenAiServer()
                .replyText("{\"action\":\"tool_call\",\"toolName\":\"ghost\",\"toolInput\":{}}")
                .replyText("{\"action\":\"final_answer\",\"finalAnswer\":\"ok\"}")) {
            Map<String, Object> output = run(server, "prompt", 5);

            Map<String, Object> observation = castMap(trace(output).get(0).get("observation"));
            assertTrue(String.valueOf(observation.get("error")).contains("ghost"));
            assertEquals("ok", output.get("output"));
        }
    }

    // ------------------------------------------------------------------ config

    @Test
    void toolCallMode_defaultsToNative_andRejectsTypos() {
        assertEquals("native", ReActAgentNodeExecutor.resolveToolCallMode(Map.of()));
        assertEquals("native", ReActAgentNodeExecutor.resolveToolCallMode(Map.of("toolCallMode", " ")));
        assertEquals("prompt", ReActAgentNodeExecutor.resolveToolCallMode(Map.of("toolCallMode", "PROMPT")));
        assertThrows(IllegalArgumentException.class,
                () -> ReActAgentNodeExecutor.resolveToolCallMode(Map.of("toolCallMode", "nativ")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
