package com.paiagent.service.mcp;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio MCP 服务的会话池：同一个 {@link McpServerSpec} 复用同一个子进程，不再每次调用都重新拉起进程并握手
 * （uvx/npx 冷启动动辄几秒到几十秒，初始化超时给了 90 秒）。
 *
 * <ul>
 *   <li><b>健康检查</b>：取会话时进程已退出就丢弃重建；调用超时的会话直接关掉，下一次用新进程，
 *       不让一个卡死的进程挂在池里。</li>
 *   <li><b>只在“请求肯定没发出去”时重试</b>：写管道失败（进程刚好退出）重建会话重试一次；
 *       请求已发出后才失败的不重试，因为工具可能已经执行过一次，重发会造成重复副作用。</li>
 *   <li><b>空闲回收</b>：超过 {@code paiagent.mcp.idle-minutes}（默认 10）没人用的进程会被关闭；
 *       数量超过 {@code paiagent.mcp.max-sessions}（默认 8）时先关最久没用的。</li>
 *   <li><b>工具名校验</b>：握手后缓存 {@code tools/list}，调用不存在的工具直接报错并列出可用工具，不用发请求碰运气。</li>
 *   <li>应用退出时关闭所有子进程。</li>
 * </ul>
 */
@Slf4j
@Component
public class McpSessionPool implements AutoCloseable {

    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(120);

    private final Map<String, McpStdioSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> startLocks = new ConcurrentHashMap<>();
    private final Duration initializeTimeout;
    private final Duration idleTimeout;
    private final int maxSessions;
    private final ScheduledExecutorService sweeper;
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();

    @Autowired
    public McpSessionPool(@Value("${paiagent.mcp.initialize-timeout-seconds:90}") long initializeTimeoutSeconds,
                          @Value("${paiagent.mcp.idle-minutes:10}") long idleMinutes,
                          @Value("${paiagent.mcp.max-sessions:8}") int maxSessions) {
        this(Duration.ofSeconds(initializeTimeoutSeconds), Duration.ofMinutes(idleMinutes), Duration.ofMinutes(1), maxSessions);
    }

    public McpSessionPool(Duration initializeTimeout, Duration idleTimeout, Duration sweepInterval, int maxSessions) {
        this.initializeTimeout = initializeTimeout;
        this.idleTimeout = idleTimeout;
        this.maxSessions = Math.max(1, maxSessions);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        long periodMs = Math.max(50, Math.min(sweepInterval.toMillis(), idleTimeout.toMillis() / 2));
        sweeper.scheduleWithFixedDelay(this::sweepIdle, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public JSONObject callTool(McpServerSpec spec, String toolName, JSONObject arguments, Duration callTimeout)
            throws IOException, InterruptedException, TimeoutException {
        String key = spec.key();
        for (int attempt = 1; ; attempt++) {
            McpStdioSession session = acquire(spec, key);
            try {
                requireTool(session, toolName);
                JSONObject result = session.callTool(toolName, arguments, callTimeout);
                session.touch();
                return result;
            } catch (McpStdioSession.McpNotDeliveredException e) {
                evict(key, session);
                if (attempt >= 2) {
                    throw e;
                }
                log.warn("MCP 会话已失效，请求尚未发出，重建会话重试一次: {}", e.getMessage());
            } catch (TimeoutException e) {
                evict(key, session);
                throw e;
            } catch (McpToolNotFoundException | McpStdioSession.McpProtocolException e) {
                throw e; // 会话本身是好的
            } catch (IOException e) {
                evict(key, session);
                throw e;
            }
        }
    }

    /** 服务端提供的工具定义（tools/list 的结果）。服务端没有声明 tools 能力时为空列表。 */
    public List<JSONObject> listTools(McpServerSpec spec) throws IOException, InterruptedException, TimeoutException {
        McpStdioSession session = acquire(spec, spec.key());
        session.touch();
        List<JSONObject> tools = session.tools();
        return tools == null ? List.of() : tools;
    }

    /** 配置被修改或删除时，立刻关掉旧配置对应的进程，不等空闲回收。 */
    public void evict(McpServerSpec spec) {
        String key = spec.key();
        McpStdioSession session = sessions.get(key);
        if (session != null) {
            evict(key, session);
        }
    }

    public Stats stats() {
        return new Stats(started.get(), reused.get(), sessions.size());
    }

    public record Stats(long started, long reused, int active) {
    }

    // ------------------------------------------------------------------ internals

    private McpStdioSession acquire(McpServerSpec spec, String key)
            throws IOException, InterruptedException, TimeoutException {
        Object lock = startLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            McpStdioSession existing = sessions.get(key);
            if (existing != null) {
                if (existing.isAlive()) {
                    existing.touch();
                    reused.incrementAndGet();
                    return existing;
                }
                log.warn("MCP 会话进程已退出，重新启动: command={}, pid={}", spec.command(), existing.pid());
                evict(key, existing);
            }
            makeRoom();

            McpStdioSession fresh = McpStdioSession.start(spec, key.substring(0, 8));
            try {
                fresh.initialize(initializeTimeout);
            } catch (IOException | TimeoutException | InterruptedException | RuntimeException e) {
                fresh.close();
                throw e;
            }
            sessions.put(key, fresh);
            started.incrementAndGet();
            log.info("MCP 会话已建立: command={}, pid={}, 当前会话数={}", spec.command(), fresh.pid(), sessions.size());
            return fresh;
        }
    }

    private void requireTool(McpStdioSession session, String toolName) throws McpToolNotFoundException {
        List<JSONObject> tools = session.tools();
        if (tools == null || tools.stream().anyMatch(tool -> toolName.equals(tool.getString("name")))) {
            return;
        }
        String available = tools.stream().map(tool -> tool.getString("name")).collect(java.util.stream.Collectors.joining(", "));
        throw new McpToolNotFoundException("MCP 服务没有提供工具 " + toolName + "，可用工具: " + available);
    }

    private void makeRoom() {
        while (sessions.size() >= maxSessions) {
            sessions.entrySet().stream()
                    .filter(entry -> entry.getValue().inFlight() == 0)
                    .max(Comparator.comparing(entry -> entry.getValue().idleFor()))
                    .ifPresentOrElse(oldest -> {
                        log.info("MCP 会话数已达上限 {}，关闭最久未用的 pid={}", maxSessions, oldest.getValue().pid());
                        evict(oldest.getKey(), oldest.getValue());
                    }, () -> {
                        throw new IllegalStateException("MCP 会话数已达上限 " + maxSessions + "，且都在使用中，请稍后重试");
                    });
        }
    }

    private void evict(String key, McpStdioSession session) {
        if (sessions.remove(key, session)) {
            log.info("MCP 会话已移除: pid={}", session.pid());
        }
        session.close();
    }

    private void sweepIdle() {
        try {
            sessions.forEach((key, session) -> {
                if (session.inFlight() == 0 && session.idleFor().compareTo(idleTimeout) > 0) {
                    log.info("MCP 会话空闲超过 {} 分钟，回收: pid={}", idleTimeout.toMinutes(), session.pid());
                    evict(key, session);
                }
            });
        } catch (RuntimeException e) {
            log.warn("MCP 会话空闲回收出错: {}", e.getMessage());
        }
    }

    @PreDestroy
    @Override
    public void close() {
        sweeper.shutdownNow();
        sessions.forEach(this::evict);
    }

    /** 服务端没有这个工具。 */
    public static class McpToolNotFoundException extends IOException {
        public McpToolNotFoundException(String message) {
            super(message);
        }
    }
}
