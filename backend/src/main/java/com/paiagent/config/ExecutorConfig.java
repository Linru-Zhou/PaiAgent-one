package com.paiagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流执行相关的两个线程池，故意拆开：
 * - executionPool：每个"整条工作流执行"占一个线程。有界队列 + 拒绝策略（满了直接拒绝，而不是无限排队或无限建线程），
 *   这是对外的背压：请求超过承载能力时快速失败，而不是把整个服务拖垮。
 * - nodePool：同一层互不依赖的节点并发执行用。和 executionPool 分开，避免"整条执行等待节点任务、节点任务排在
 *   执行任务后面"造成的线程饥饿死锁；队列满时由调用线程自己执行（CallerRuns），退化成串行而不是丢任务。
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "executionPool", destroyMethod = "shutdown")
    public ThreadPoolExecutor executionPool(
            @Value("${paiagent.executor.execution.size:8}") int size,
            @Value("${paiagent.executor.execution.queue:32}") int queue) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), namedThreads("wf-exec"), new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Bean(name = "nodePool", destroyMethod = "shutdown")
    public ThreadPoolExecutor nodePool(
            @Value("${paiagent.executor.node.size:16}") int size,
            @Value("${paiagent.executor.node.queue:64}") int queue) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), namedThreads("wf-node"), new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
