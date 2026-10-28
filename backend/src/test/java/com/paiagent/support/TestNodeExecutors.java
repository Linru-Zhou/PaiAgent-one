package com.paiagent.support;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可控的测试节点：
 * - test_task：可配置耗时 sleepMs；可配置前 failTimes 次调用失败；记录调用次数与起止时间，用于验证并发/幂等/续跑。
 */
@TestConfiguration
public class TestNodeExecutors {

    private static final Map<String, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();
    private static final List<Span> SPANS = Collections.synchronizedList(new ArrayList<>());

    public record Span(String nodeId, long startNanos, long endNanos, String thread) {
    }

    public static void reset() {
        INVOCATIONS.clear();
        SPANS.clear();
    }

    public static int invocations(String nodeId) {
        AtomicInteger counter = INVOCATIONS.get(nodeId);
        return counter == null ? 0 : counter.get();
    }

    public static List<Span> spans() {
        synchronized (SPANS) {
            return new ArrayList<>(SPANS);
        }
    }

    /** 模拟阻塞在不响应中断的调用上的节点（比如某些网络读）：被中断也继续干到自己结束。 */
    private static void sleepIgnoringInterrupts(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long remainingMs;
        while ((remainingMs = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException ignored) {
                // 故意吞掉
            }
        }
    }

    @Bean
    public NodeExecutor testTaskNodeExecutor() {
        return new NodeExecutor() {
            @Override
            public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
                int attempt = INVOCATIONS.computeIfAbsent(node.getId(), k -> new AtomicInteger()).incrementAndGet();
                long start = System.nanoTime();
                try {
                    Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
                    long sleepMs = data.get("sleepMs") instanceof Number n ? n.longValue() : 0L;
                    int failTimes = data.get("failTimes") instanceof Number n ? n.intValue() : 0;
                    if (sleepMs > 0) {
                        if (Boolean.TRUE.equals(data.get("ignoreInterrupt"))) {
                            sleepIgnoringInterrupts(sleepMs);
                        } else {
                            Thread.sleep(sleepMs);
                        }
                    }
                    if (attempt <= failTimes) {
                        throw new IllegalStateException("模拟节点失败: " + node.getId() + " 第 " + attempt + " 次调用");
                    }
                    Map<String, Object> output = new LinkedHashMap<>();
                    Object upstream = input.get("output") != null ? input.get("output") : input.get("input");
                    output.put("output", node.getId() + "(" + upstream + ")");
                    return output;
                } finally {
                    SPANS.add(new Span(node.getId(), start, System.nanoTime(), Thread.currentThread().getName()));
                }
            }

            @Override
            public String getSupportedNodeType() {
                return "test_task";
            }
        };
    }
}
