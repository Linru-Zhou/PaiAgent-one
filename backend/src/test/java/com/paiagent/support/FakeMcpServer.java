package com.paiagent.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.service.mcp.McpServerSpec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一个真正的 stdio MCP 服务（独立的 JVM 进程），给会话池的测试用。
 * 它在每次 echo 的结果里带上自己的 pid，所以测试能直接看出“是不是同一个进程”。
 *
 * <p>工具：
 * <ul>
 *   <li>{@code echo}：回显参数，附带 pid 和已收到的 tools/call 次数</li>
 *   <li>{@code slow}：睡 {@code ms} 毫秒再回复</li>
 *   <li>{@code exit_after_reply}：先回复，再退出进程（模拟服务在两次调用之间挂掉）</li>
 *   <li>{@code crash}：不回复直接退出（模拟调用中途崩溃）</li>
 * </ul>
 * 启动时往 stdout 打一行非 JSON 的日志，用来验证客户端会跳过它。
 */
public class FakeMcpServer {

    /** 启动本类所需的命令（用当前测试的 JVM 和 classpath）。 */
    public static McpServerSpec spec(Map<String, String> env) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new McpServerSpec(java, List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()), env);
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        long pid = ProcessHandle.current().pid();
        int calls = 0;
        out.println("fake-mcp-server starting (this line is not JSON)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JSONObject request = JSON.parseObject(line);
            String method = request.getString("method");
            Object id = request.get("id");
            if (id == null) {
                continue; // notifications/initialized
            }
            switch (method) {
                case "initialize" -> reply(out, id, new JSONObject()
                        .fluentPut("protocolVersion", "2024-11-05")
                        .fluentPut("capabilities", new JSONObject().fluentPut("tools", new JSONObject()))
                        .fluentPut("serverInfo", new JSONObject().fluentPut("name", "fake").fluentPut("version", "1")));
                case "tools/list" -> reply(out, id, new JSONObject().fluentPut("tools", new JSONArray()
                        .fluentAdd(tool("echo")).fluentAdd(tool("slow"))
                        .fluentAdd(tool("exit_after_reply")).fluentAdd(tool("crash"))));
                case "tools/call" -> {
                    calls++;
                    JSONObject params = request.getJSONObject("params");
                    JSONObject arguments = params.getJSONObject("arguments");
                    switch (params.getString("name")) {
                        case "slow" -> {
                            Thread.sleep(arguments.getLongValue("ms"));
                            reply(out, id, text("slept"));
                        }
                        case "crash" -> Runtime.getRuntime().halt(1);
                        case "exit_after_reply" -> {
                            reply(out, id, text("bye pid=" + pid));
                            Runtime.getRuntime().halt(0);
                        }
                        default -> reply(out, id, text("pid=" + pid + " calls=" + calls + " args=" + arguments.toJSONString()));
                    }
                }
                default -> out.println(new JSONObject().fluentPut("jsonrpc", "2.0").fluentPut("id", id)
                        .fluentPut("error", new JSONObject().fluentPut("code", -32601).fluentPut("message", "no " + method)));
            }
        }
    }

    private static JSONObject tool(String name) {
        return new JSONObject().fluentPut("name", name).fluentPut("description", name)
                .fluentPut("inputSchema", new JSONObject().fluentPut("type", "object"));
    }

    private static JSONObject text(String text) {
        return new JSONObject().fluentPut("content", new JSONArray()
                .fluentAdd(new JSONObject().fluentPut("type", "text").fluentPut("text", text)));
    }

    private static void reply(PrintStream out, Object id, JSONObject result) throws IOException {
        out.println(new JSONObject().fluentPut("jsonrpc", "2.0").fluentPut("id", id).fluentPut("result", result).toJSONString());
    }
}
