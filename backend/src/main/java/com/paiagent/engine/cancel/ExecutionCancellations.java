package com.paiagent.engine.cancel;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前进程里正在运行的执行 → 它们的取消标记。引擎在执行开始时登记、结束时注销。
 *
 * <p>范围说明：这是进程内的登记表，所以取消只对<b>运行在本进程里</b>的执行有效。PaiAgent 是单实例设计
 * （见 AGENTS.md），多实例部署时需要把取消请求路由到持有该执行的实例，或者改成"写库 + 各实例轮询"。
 */
@Component
public class ExecutionCancellations {

    private final Map<Long, CancelToken> tokens = new ConcurrentHashMap<>();

    public CancelToken register(Long executionId) {
        CancelToken token = new CancelToken();
        tokens.put(executionId, token);
        return token;
    }

    public void unregister(Long executionId) {
        if (executionId != null) {
            tokens.remove(executionId);
        }
    }

    /** 没有登记（已结束，或者根本不在本进程里跑）时返回 null。 */
    public CancelToken find(Long executionId) {
        return executionId == null ? null : tokens.get(executionId);
    }
}
