package com.paiagent.engine;

import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.support.FlowBuilder;
import com.paiagent.support.PaiAgentSpringTest;
import com.paiagent.support.TestDb;
import com.paiagent.support.TestNodeExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 背压：旧实现每个 SSE 请求 new 一个线程（无上限）；新实现走有界线程池，超载快速拒绝。
 * 用同一条"单节点耗时 300ms"的工作流、40 个同时到达的请求做 A/B 对比。
 */
@PaiAgentSpringTest
class ExecutionBackpressureTest {

    private static final long FLOW_ID = 9_100_004L;
    private static final int REQUESTS = 40;

    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    @BeforeEach
    void setUp() {
        TestNodeExecutors.reset();
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    @AfterEach
    void tearDown() {
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
    }

    private Workflow slowWorkflow() {
        return new FlowBuilder().input("in").task("slow", 300, 0).output("out")
                .edge("in", "slow").edge("slow", "out").build(FLOW_ID, "dag");
    }

    /** 采样峰值线程数的后台线程 */
    private static class PeakSampler implements AutoCloseable {
        private final ThreadMXBean mx;
        private final AtomicInteger peak = new AtomicInteger();
        private final Thread sampler;
        private volatile boolean running = true;

        PeakSampler(ThreadMXBean mx) {
            this.mx = mx;
            peak.set(mx.getThreadCount());
            sampler = new Thread(() -> {
                while (running) {
                    peak.accumulateAndGet(mx.getThreadCount(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "peak-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        int peak() {
            return peak.get();
        }

        @Override
        public void close() {
            running = false;
        }
    }

    @Test
    void boundedPoolRejectsOverloadWhileThreadPerRequestDoesNot() throws Exception {
        Workflow workflow = slowWorkflow();

        // ---- A. 旧方式：每个请求直接 new Thread（对应修改前的 ExecutionController SSE 实现）
        int baseline = threads.getThreadCount();
        AtomicInteger oldCompleted = new AtomicInteger();
        long oldMs;
        int oldPeak;
        try (PeakSampler sampler = new PeakSampler(threads)) {
            long start = System.nanoTime();
            List<Thread> started = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                Thread t = new Thread(() -> {
                    engine.execute(workflow, "x");
                    oldCompleted.incrementAndGet();
                });
                started.add(t);
                t.start();
            }
            for (Thread t : started) {
                t.join();
            }
            oldMs = (System.nanoTime() - start) / 1_000_000;
            oldPeak = sampler.peak() - baseline;
        }
        TestDb.deleteExecutions(FLOW_ID, recordMapper, snapshotMapper);
        TestNodeExecutors.reset();

        // ---- B. 新方式：有界线程池（4 个执行线程 + 8 个排队位），超载直接拒绝
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        ExecutionDispatcher dispatcher = new ExecutionDispatcher(pool);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        long newMs;
        int newPeak;
        try (PeakSampler sampler = new PeakSampler(threads)) {
            long start = System.nanoTime();
            List<Thread> clients = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                Thread client = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        futures.add(dispatcher.submit(() -> engine.execute(workflow, "x")));
                        accepted.incrementAndGet();
                    } catch (ExecutionRejectedException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    }
                });
                clients.add(client);
                client.start();
            }
            ready.await();
            go.countDown();
            for (Thread client : clients) {
                client.join();
            }
            for (Future<?> future : new ArrayList<>(futures)) {
                future.get();
            }
            newMs = (System.nanoTime() - start) / 1_000_000;
            newPeak = pool.getLargestPoolSize(); // 执行池历史上同时存在的最大线程数（精确值）
        }
        pool.shutdown();

        System.out.printf("[背压] %d 个并发请求，每个工作流耗时约 300ms%n", REQUESTS);
        System.out.printf("[背压] 旧方式(每请求一个线程)：全部接受并执行=%d，峰值新增线程≈%d，总耗时=%dms%n", oldCompleted.get(), oldPeak, oldMs);
        System.out.printf("[背压] 新方式(有界池 4+队列8)：接受=%d，拒绝=%d，执行池最大线程数=%d，已接受的全部完成，总耗时=%dms，拒绝计数=%d%n",
                accepted.get(), rejected.get(), newPeak, newMs, dispatcher.stats().rejected());

        assertEquals(REQUESTS, oldCompleted.get(), "旧方式不限流：40 个请求全部各开一个线程执行");
        assertTrue(oldPeak >= REQUESTS - 5, "旧方式峰值线程数应接近请求数，实际 " + oldPeak);
        assertTrue(accepted.get() <= 12, "新方式最多接受 4 个执行中 + 8 个排队 = 12 个，实际 " + accepted.get());
        assertEquals(REQUESTS, accepted.get() + rejected.get());
        assertTrue(rejected.get() >= REQUESTS - 12);
        assertEquals(rejected.get(), dispatcher.stats().rejected());
        assertEquals(4, newPeak, "执行池线程数应被严格限制在 4");
    }
}
