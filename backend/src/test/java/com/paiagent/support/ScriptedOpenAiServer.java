package com.paiagent.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地假的 OpenAI 兼容服务，按剧本依次回复，并记录每个请求体，用来断言“客户端到底发了什么”。
 * 剧本用完后重复最后一条回复。
 */
public class ScriptedOpenAiServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> replies = new ArrayList<>();
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();

    public ScriptedOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(JSON.parseObject(requestBody));
                String reply;
                synchronized (replies) {
                    int index = Math.min(requests.size(), replies.size()) - 1;
                    reply = replies.get(index);
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** 模型直接回复一段文字。 */
    public ScriptedOpenAiServer replyText(String content) {
        return add(message(content, null), "stop");
    }

    /** 模型要求调用一个工具。 */
    public ScriptedOpenAiServer replyToolCall(String callId, String name, String argumentsJson) {
        JSONObject call = new JSONObject();
        call.put("id", callId);
        call.put("type", "function");
        call.put("function", new JSONObject().fluentPut("name", name).fluentPut("arguments", argumentsJson));
        return add(message(null, new JSONArray().fluentAdd(call)), "tool_calls");
    }

    /** 模型既没有文字也没有工具调用（异常回复）。 */
    public ScriptedOpenAiServer replyNothing() {
        return add(message(null, null), "stop");
    }

    private JSONObject message(String content, JSONArray toolCalls) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content);
        if (toolCalls != null) {
            message.put("tool_calls", toolCalls);
        }
        return message;
    }

    private ScriptedOpenAiServer add(JSONObject message, String finishReason) {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        JSONObject body = new JSONObject();
        body.put("id", "x");
        body.put("object", "chat.completion");
        body.put("created", 0);
        body.put("model", "fake");
        body.put("choices", new JSONArray().fluentAdd(choice));
        body.put("usage", new JSONObject()
                .fluentPut("prompt_tokens", 10).fluentPut("completion_tokens", 5).fluentPut("total_tokens", 15));
        synchronized (replies) {
            replies.add(body.toJSONString());
        }
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 收到的第 n 个请求（从 0 开始）的 JSON 请求体。 */
    public JSONObject request(int index) {
        return requests.get(index);
    }

    public int requestCount() {
        return requests.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
