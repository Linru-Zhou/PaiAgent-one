package com.paiagent.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地假的 OpenAI 兼容服务：可控响应延迟、前 N 次返回错误状态码，并记录请求数与并发峰值。
 */
public class FakeOpenAiServer implements AutoCloseable {

    private final HttpServer server;
    private volatile long delayMs = 0;
    private volatile int failFirst = 0;
    private volatile int failStatus = 503;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();

    public FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            int n = requests.incrementAndGet();
            int now = current.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                exchange.getRequestBody().readAllBytes();
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                }
                boolean fail = n <= failFirst;
                String body = fail
                        ? "{\"error\":{\"message\":\"simulated failure\",\"type\":\"server_error\"}}"
                        : "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"fake\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(fail ? failStatus : 200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                } catch (IOException ignored) {
                    // 客户端已因超时断开
                }
            } finally {
                current.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeOpenAiServer delayMs(long delayMs) {
        this.delayMs = delayMs;
        return this;
    }

    public FakeOpenAiServer failFirst(int count, int status) {
        this.failFirst = count;
        this.failStatus = status;
        return this;
    }

    public int requests() {
        return requests.get();
    }

    public int maxConcurrent() {
        return maxConcurrent.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
