package com.paiagent.config;

import com.paiagent.engine.ExecutionDispatcher;
import com.paiagent.support.PaiAgentSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康检查：探针不需要登录就能访问（Docker / 负载均衡没有令牌）、匿名时不泄露细节、不暴露危险端点；
 * 执行线程池满了就绪探针要报"暂时不可用"。
 */
@PaiAgentSpringTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_isReachableWithoutLogin_andReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessProbes_areAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void anonymousResponseCarriesNoDetails() throws Exception {
        String body = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("components"), "匿名访问不应返回组件细节: " + body);
        assertFalse(body.contains("jdbc:"), "更不能带出连接串: " + body);
    }

    @Test
    void sensitiveEndpointsAreNotExposed() throws Exception {
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/configprops"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void apiStillRequiresLogin() throws Exception {
        // 放开 /actuator/health 不能顺带放开业务接口
        mockMvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
    }

    @Test
    void executionPool_saturatedQueueMeansOutOfService_notDown() throws Exception {
        // 1 个线程 + 容量 1 的队列：一个任务占住线程，一个任务占满队列，池就是饱和的
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        ExecutionDispatcher dispatcher = new ExecutionDispatcher(pool);
        ExecutionPoolHealthIndicator indicator = new ExecutionPoolHealthIndicator(dispatcher);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertEquals(Status.UP, indicator.health().getStatus(), "空闲时应该 UP");

            dispatcher.execute(() -> awaitQuietly(release));
            dispatcher.execute(() -> awaitQuietly(release));
            Health saturated = indicator.health();

            assertEquals(Status.OUT_OF_SERVICE, saturated.getStatus(), "队列满了：暂时不接新执行，但进程没坏");
            assertEquals(1, saturated.getDetails().get("queued"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
