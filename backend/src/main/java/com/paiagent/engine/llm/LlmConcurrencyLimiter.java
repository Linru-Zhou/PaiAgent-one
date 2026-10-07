package com.paiagent.engine.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 按供应商隔离的并发限额（舱壁）：每个供应商最多同时 N 个在途调用，超过的排队等待，
 * 等待超时就快速失败。这样一个供应商限流/变慢，不会把所有执行线程都拖进去，也不会把对方打到 429。
 */
@Component
public class LlmConcurrencyLimiter {

    private final int permitsPerProvider;
    private final long acquireTimeoutMs;
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public LlmConcurrencyLimiter(@Value("${paiagent.llm.max-concurrent-per-provider:8}") int permitsPerProvider,
                                 @Value("${paiagent.llm.acquire-timeout-ms:30000}") long acquireTimeoutMs) {
        this.permitsPerProvider = Math.max(1, permitsPerProvider);
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public static LlmConcurrencyLimiter defaults() {
        return new LlmConcurrencyLimiter(8, 30_000);
    }

    /** 申请名额，调用方必须在 finally 里 release。 */
    public Runnable acquire(String provider) {
        Semaphore semaphore = semaphores.computeIfAbsent(provider, k -> new Semaphore(permitsPerProvider, true));
        try {
            if (!semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new LlmBusyException("供应商 " + provider + " 的并发调用已达上限(" + permitsPerProvider
                        + ")，等待 " + acquireTimeoutMs / 1000.0 + "s 仍无空闲名额，请稍后重试或降低并发");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmBusyException("等待供应商 " + provider + " 的调用名额时被中断");
        }
        return semaphore::release;
    }

    public <T> T execute(String provider, Supplier<T> call) {
        Runnable release = acquire(provider);
        try {
            return call.get();
        } finally {
            release.run();
        }
    }

    public int availablePermits(String provider) {
        Semaphore semaphore = semaphores.get(provider);
        return semaphore == null ? permitsPerProvider : semaphore.availablePermits();
    }
}
