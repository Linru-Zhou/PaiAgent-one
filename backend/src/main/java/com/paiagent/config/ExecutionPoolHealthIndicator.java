package com.paiagent.config;

import com.paiagent.engine.ExecutionDispatcher;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 执行线程池的健康状态：队列满了意味着新的执行会被拒绝（接口返回 429），
 * 这时就绪探针应该告诉负载均衡"先别往这个实例发新的执行"，而不是让每个请求都撞上 429。
 * 队列没满时是 UP；队列满了是 OUT_OF_SERVICE（暂时不可用，不是进程坏了，所以不用重启）。
 * 细节（活动线程数、队列长度、累计拒绝数）放在 details 里，匿名访问时不会返回。
 */
// bean 名默认是 executionPoolHealthIndicator，Actuator 去掉 HealthIndicator 后缀，健康项就叫 executionPool；
// 不能显式叫 executionPool：那个名字已经被真正的线程池 bean 用了
@Component
public class ExecutionPoolHealthIndicator implements HealthIndicator {

    private final ExecutionDispatcher dispatcher;

    public ExecutionPoolHealthIndicator(ExecutionDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public Health health() {
        ExecutionDispatcher.Stats stats = dispatcher.stats();
        boolean saturated = stats.queueCapacity() > 0 && stats.queued() >= stats.queueCapacity();
        return Health.status(saturated ? Status.OUT_OF_SERVICE : Status.UP)
                .withDetail("active", stats.active())
                .withDetail("queued", stats.queued())
                .withDetail("poolSize", stats.poolSize())
                .withDetail("queueCapacity", stats.queueCapacity())
                .withDetail("rejected", stats.rejected())
                .build();
    }
}
