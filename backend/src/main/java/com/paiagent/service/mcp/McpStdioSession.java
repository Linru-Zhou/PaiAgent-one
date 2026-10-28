package com.paiagent.service.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条到 stdio MCP 服务的长连接：一个子进程 + 一个读线程。
 *
 * <p>读线程把服务端的每一行 JSON 按 {@code id} 分发给等待它的请求，所以多个线程可以同时在同一个连接上发请求，
 * 某个请求超时也不会堵住后面的请求（之前的实现是每次请求临时占用读线程，超时后读线程会一直卡在 readLine 上）。
 * 服务端打到 stdout 的非 JSON 行（启动日志之类）会被跳过；服务端发来的 {@code ping} 会被应答。
 */
@Slf4j
public final class McpStdioSession implements Closeable {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final String label;
    private final Object writeLock = new Object();
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean closed;
    private volatile long lastUsedNanos = System.nanoTime();
    private volatile List<JSONObject> tools;

    private McpStdioSession(Process process, String label) {
        this.process = process;
        this.label = label;
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        daemon("mcp-reader-" + label, this::readLoop).start();
        daemon("mcp-stderr-" + label, this::collectStderr).start();
    }

    public static McpStdioSession start(McpServerSpec spec, String label) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(spec.command());
        commandLine.addAll(spec.args());
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        spec.env().forEach((name, value) -> {
            if (name != null && !name.isBlank()) {
                builder.environment().put(name, value == null ? "" : value);
            }
        });
        return new McpStdioSession(builder.start(), label);
    }

    // ------------------------------------------------------------------ protocol

    /** initialize → notifications/initialized，服务端声明了 tools 能力时再 tools/list 一次并缓存。 */
    public void initialize(Duration timeout) throws IOException, InterruptedException, TimeoutException {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", new JSONObject());
        params.put("clientInfo", Map.of("name", "PaiAgent-one", "version", "0.1.0"));
        JSONObject result = request("initialize", params, timeout);
        log.info("MCP 会话已初始化: {}, server={}, protocol={}",
                label, result.get("serverInfo"), result.getString("protocolVersion"));
        notify("notifications/initialized", new JSONObject());

        JSONObject capabilities = result.getJSONObject("capabilities");
        if (capabilities != null && capabilities.containsKey("tools")) {
            try {
                tools = fetchTools(timeout);
            } catch (IOException | TimeoutException e) {
                // tools/list 只用来提前校验工具名，拿不到不影响调用
                log.warn("MCP tools/list 失败，跳过工具名校验: {}, {}", label, e.getMessage());
            }
        }
    }

    private List<JSONObject> fetchTools(Duration timeout) throws IOException, InterruptedException, TimeoutException {
        List<JSONObject> all = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject params = new JSONObject();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JSONObject page = request("tools/list", params, timeout);
            JSONArray items = page.getJSONArray("tools");
            if (items != null) {
                for (Object item : items) {
                    if (item instanceof JSONObject tool) {
                        all.add(tool);
                    }
                }
            }
            cursor = page.getString("nextCursor");
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(all);
    }

    /** initialize 时缓存的工具定义；服务端没声明 tools 能力或 tools/list 失败时为 null。 */
    public List<JSONObject> tools() {
        return tools;
    }

    public JSONObject callTool(String name, JSONObject arguments, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", arguments == null ? new JSONObject() : arguments);
        return request("tools/call", params, timeout);
    }

    private JSONObject request(String method, JSONObject params, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        long id = requestIds.getAndIncrement();
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);
        inFlight.incrementAndGet();
        try {
            JSONObject message = new JSONObject();
            message.put("jsonrpc", "2.0");
            message.put("id", id);
            message.put("method", method);
            message.put("params", params);
            try {
                write(message);
            } catch (IOException e) {
                throw new McpNotDeliveredException(label + " 写入失败（进程可能已退出）: " + e.getMessage(), e);
            }

            JSONObject response;
            try {
                response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new TimeoutException(method + " 超过 " + timeout.toSeconds() + " 秒没有响应; stderr=" + recentStderr());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("MCP 调用失败: " + cause, cause);
            }

            JSONObject error = response.getJSONObject("error");
            if (error != null) {
                throw new McpProtocolException(error.getIntValue("code"), "MCP 调用失败: " + error.toJSONString());
            }
            JSONObject result = response.getJSONObject("result");
            return result != null ? result : new JSONObject();
        } finally {
            pending.remove(id);
            inFlight.decrementAndGet();
            lastUsedNanos = System.nanoTime();
        }
    }

    private void notify(String method, JSONObject params) throws IOException {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        write(message);
    }

    private void write(JSONObject message) throws IOException {
        if (closed) {
            throw new IOException("会话已关闭");
        }
        synchronized (writeLock) {
            stdin.write(message.toJSONString());
            stdin.write("\n");
            stdin.flush();
        }
    }

    // ------------------------------------------------------------------ reader

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                JSONObject message = parse(line);
                if (message != null) {
                    dispatch(message);
                }
            }
            failPending("MCP 服务关闭了标准输出（进程已退出）");
        } catch (IOException e) {
            failPending("读取 MCP 服务输出失败: " + e.getMessage());
        }
    }

    private JSONObject parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            log.debug("MCP stdout 非 JSON 行，已跳过: {} {}", label, trimmed);
            return null;
        }
        try {
            return JSON.parseObject(trimmed);
        } catch (RuntimeException e) {
            log.debug("MCP stdout 无法解析，已跳过: {} {}", label, trimmed);
            return null;
        }
    }

    private void dispatch(JSONObject message) {
        Object id = message.get("id");
        if (message.containsKey("method")) {
            if (id != null) {
                answerServerRequest(id, message.getString("method"));
            }
            return; // 其余是服务端通知（日志、进度），对只做工具调用的客户端没有用
        }
        if (id instanceof Number number) {
            CompletableFuture<JSONObject> future = pending.get(number.longValue());
            if (future != null) {
                future.complete(message);
            }
        }
    }

    /** 服务端也会向客户端发请求。只会应答 ping，其他一律回“方法不存在”，免得服务端一直等。 */
    private void answerServerRequest(Object id, String method) {
        JSONObject reply = new JSONObject();
        reply.put("jsonrpc", "2.0");
        reply.put("id", id);
        if ("ping".equals(method)) {
            reply.put("result", new JSONObject());
        } else {
            reply.put("error", new JSONObject().fluentPut("code", -32601).fluentPut("message", "Method not found: " + method));
        }
        try {
            write(reply);
        } catch (IOException e) {
            log.debug("应答 MCP 服务端请求失败: {} {}", label, e.getMessage());
        }
    }

    private void failPending(String reason) {
        closed = true;
        IOException failure = new IOException(reason + "; stderr=" + recentStderr());
        pending.values().forEach(future -> future.completeExceptionally(failure));
    }

    private void collectStderr() {
        try (BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = stderr.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                synchronized (stderrTail) {
                    stderrTail.addLast(text);
                    while (stderrTail.size() > 8) {
                        stderrTail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程关闭时的正常收尾
        }
    }

    private String recentStderr() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public void touch() {
        lastUsedNanos = System.nanoTime();
    }

    public Duration idleFor() {
        return Duration.ofNanos(System.nanoTime() - lastUsedNanos);
    }

    public long pid() {
        return process.pid();
    }

    @Override
    public void close() {
        closed = true;
        failPending("MCP 会话已关闭");
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 对端可能已经退出
        }
        // 服务常常是 uvx/npx 拉起的二级进程，只杀父进程会留下孤儿
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    /** 请求没有发出去（会话已关闭或写管道失败），可以放心地换一个新会话重试。 */
    public static class McpNotDeliveredException extends IOException {
        public McpNotDeliveredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 服务端按 JSON-RPC 返回了错误（会话本身是好的）。 */
    public static class McpProtocolException extends IOException {
        private final int code;

        public McpProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
