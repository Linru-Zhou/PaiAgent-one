package com.paiagent.engine.llm;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.support.FakeOpenAiServer;
import com.paiagent.support.PaiAgentSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大模型调用的稳定性：读超时、受控重试、按供应商限流。对端用本地假服务模拟，行为完全可控。
 */
@PaiAgentSpringTest
class LlmResilienceTest {

    @Autowired
    private List<NodeExecutor> executors;

    private ChatClientFactory factory(LlmCallPolicy policy, LlmConcurrencyLimiter limiter) {
        ChatClientFactory factory = new ChatClientFactory();
        factory.setPolicy(policy);
        factory.setLimiter(limiter);
        return factory;
    }

    private String ask(ChatClientFactory factory, String provider, String baseUrl) {
        ChatClient client = factory.createClient(provider, baseUrl, "sk-test", "fake", 0.3);
        return client.prompt().user("hi").call().content();
    }

    private static boolean causeChainContains(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void hungProvider_failsFastByReadTimeout_andIsNotRetried() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().delayMs(5000)) {
            ChatClientFactory factory = factory(new LlmCallPolicy(2000, 800, 3, 50, 200, "", 0), LlmConcurrencyLimiter.defaults());

            long start = System.nanoTime();
            assertThrows(RuntimeException.class, () -> ask(factory, "deepseek", server.baseUrl()));
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            System.out.printf("[LLM稳定性] 对端挂住5秒、读超时0.8秒：%dms 就失败返回（旧方式等满6540ms，真挂死则永远等），对端请求数=%d%n",
                    elapsed, server.requests());
            assertTrue(elapsed < 3000, "应在读超时附近失败，实际 " + elapsed);
            assertEquals(1, server.requests(), "读超时不应触发重试（重试只会让延迟翻倍）");
        }
    }

    @Test
    void transientFailures_areRetriedWithBoundedBackoff() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().failFirst(2, 503)) {
            ChatClientFactory factory = factory(new LlmCallPolicy(2000, 5000, 3, 100, 400, "", 0), LlmConcurrencyLimiter.defaults());

            long start = System.nanoTime();
            String content = ask(factory, "deepseek", server.baseUrl());
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            System.out.printf("[LLM稳定性] 前2次返回503：%dms 内重试成功（旧方式默认退避 2s+10s，实测12048ms），对端请求数=%d%n",
                    elapsed, server.requests());
            assertEquals("ok", content);
            assertEquals(3, server.requests());
            assertTrue(elapsed < 2000, "有界退避下应很快完成，实际 " + elapsed);
        }
    }

    @Test
    void persistentFailure_stopsAfterMaxAttempts() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().failFirst(1000, 503)) {
            ChatClientFactory factory = factory(new LlmCallPolicy(2000, 5000, 3, 50, 100, "", 0), LlmConcurrencyLimiter.defaults());

            assertThrows(RuntimeException.class, () -> ask(factory, "deepseek", server.baseUrl()));

            System.out.printf("[LLM稳定性] 持续503：共尝试 %d 次后放弃（旧方式默认最多 10 次、退避最长 3 分钟）%n", server.requests());
            assertEquals(3, server.requests());
        }
    }

    @Test
    void clientErrors_areNotRetried() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().failFirst(1000, 400)) {
            ChatClientFactory factory = factory(new LlmCallPolicy(2000, 5000, 3, 50, 100, "", 0), LlmConcurrencyLimiter.defaults());

            assertThrows(RuntimeException.class, () -> ask(factory, "deepseek", server.baseUrl()));
            assertEquals(1, server.requests(), "4xx 是请求本身的问题（如 Key 无效），重试没有意义");
        }
    }

    @Test
    void limiterCapsConcurrentCallsPerProvider() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().delayMs(300)) {
            ChatClientFactory factory = factory(LlmCallPolicy.defaults(), new LlmConcurrencyLimiter(4, 20_000));
            AtomicInteger ok = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();

            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                Thread t = new Thread(() -> {
                    ask(factory, "deepseek", server.baseUrl());
                    ok.incrementAndGet();
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            System.out.printf("[LLM稳定性] 20 个并发调用、供应商限额4：对端并发峰值=%d（旧方式=20），全部成功=%d，总耗时=%dms%n",
                    server.maxConcurrent(), ok.get(), elapsed);
            assertEquals(20, ok.get(), "超出限额的请求应排队等待而不是失败");
            assertTrue(server.maxConcurrent() <= 4, "对端并发峰值应被限制在 4，实际 " + server.maxConcurrent());
        }
    }

    @Test
    void limiterFailsFastWhenNoPermitBecomesAvailableInTime() throws Exception {
        try (FakeOpenAiServer server = new FakeOpenAiServer().delayMs(1500)) {
            ChatClientFactory factory = factory(LlmCallPolicy.defaults(), new LlmConcurrencyLimiter(1, 200));
            AtomicInteger busy = new AtomicInteger();
            AtomicInteger ok = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Thread t = new Thread(() -> {
                    try {
                        ask(factory, "deepseek", server.baseUrl());
                        ok.incrementAndGet();
                    } catch (RuntimeException e) {
                        if (causeChainContains(e, LlmBusyException.class)) {
                            busy.incrementAndGet();
                        }
                    }
                });
                threads.add(t);
                t.start();
                Thread.sleep(50);
            }
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(1, ok.get());
            assertEquals(1, busy.get(), "名额被占满且等待超时的请求应快速失败并给出明确原因");
        }
    }

    @Test
    void limiterIsolatesProviders() throws Exception {
        try (FakeOpenAiServer slow = new FakeOpenAiServer().delayMs(1500); FakeOpenAiServer fast = new FakeOpenAiServer()) {
            ChatClientFactory factory = factory(LlmCallPolicy.defaults(), new LlmConcurrencyLimiter(1, 200));
            Thread occupier = new Thread(() -> ask(factory, "deepseek", slow.baseUrl()));
            occupier.start();
            Thread.sleep(150);

            long start = System.nanoTime();
            assertEquals("ok", ask(factory, "qwen", fast.baseUrl()), "另一个供应商不受影响");
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsed < 1000, "不应被 deepseek 的占用拖慢，实际 " + elapsed);
            occupier.join();
        }
    }

    @Test
    void realNodeExecutorPathUsesTheSameProtections() throws Exception {
        NodeExecutor deepseek = executors.stream()
                .filter(e -> "deepseek".equals(e.getSupportedNodeType())).findFirst().orElseThrow();
        try (FakeOpenAiServer server = new FakeOpenAiServer().failFirst(1, 503)) {
            WorkflowNode node = new WorkflowNode();
            node.setId("llm-1");
            node.setType("deepseek");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", "deepseek");
            data.put("apiUrl", server.baseUrl());
            data.put("apiKey", "sk-test");
            data.put("model", "fake");
            data.put("prompt", "回答: {{q}}");
            data.put("inputParams", List.of(Map.of("name", "q", "type", "input", "value", "你好")));
            node.setData(data);

            Map<String, Object> output = deepseek.execute(node, new LinkedHashMap<>(Map.of("input", "你好")));

            assertEquals("ok", output.get("output"));
            assertEquals("fake", output.get("model"));
            assertEquals(2, server.requests(), "第一次 503 后自动重试成功");
        }
    }
}
