package com.paiagent.engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工作流执行的统一入口：所有执行（同步、SSE、异步）都经过这里提交到有界线程池，
 * 超过承载能力时抛 {@link ExecutionRejectedException}，由接口层转成"系统繁忙"。
 */
@Component
public class ExecutionDispatcher {

    public record Stats(int active, int queued, int poolSize, int queueCapacity, long rejected) {
    }

    private final ThreadPoolExecutor pool;
    private final AtomicLong rejected = new AtomicLong();

    @Autowired
    public ExecutionDispatcher(@Qualifier("executionPool") ThreadPoolExecutor pool) {
        this.pool = pool;
    }

    public <T> Future<T> submit(Callable<T> task) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            throw new ExecutionRejectedException("系统繁忙：当前同时执行的工作流过多，请稍后重试");
        }
    }

    public void execute(Runnable task) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            throw new ExecutionRejectedException("系统繁忙：当前同时执行的工作流过多，请稍后重试");
        }
    }

    public Stats stats() {
        return new Stats(pool.getActiveCount(), pool.getQueue().size(), pool.getMaximumPoolSize(),
                pool.getQueue().size() + pool.getQueue().remainingCapacity(), rejected.get());
    }
}
