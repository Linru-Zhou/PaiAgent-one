package com.paiagent.service.mcp;

import com.alibaba.fastjson2.JSONObject;
import com.paiagent.support.FakeMcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 会话池。对端是一个真实的子进程（{@link FakeMcpServer}），所以“有没有复用进程”直接看 pid。
 */
class McpSessionPoolTest {

    private static final Duration CALL = Duration.ofSeconds(10);

    private final List<McpSessionPool> pools = new ArrayList<>();

    private McpSessionPool pool(Duration idle) {
        McpSessionPool pool = new McpSessionPool(Duration.ofSeconds(30), idle, Duration.ofMillis(100), 8);
        pools.add(pool);
        return pool;
    }

    @AfterEach
    void closePools() {
        pools.forEach(McpSessionPool::close);
    }

    private static JSONObject args(String key, Object value) {
        return new JSONObject().fluentPut(key, value);
    }

    private static String text(JSONObject result) {
        return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    private static long pidOf(JSONObject result) {
        Matcher matcher = Pattern.compile("pid=(\\d+)").matcher(text(result));
        assertTrue(matcher.find(), text(result));
        return Long.parseLong(matcher.group(1));
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void sequentialCalls_reuseOneProcess() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());

        long t0 = System.nanoTime();
        long first = pidOf(pool.callTool(spec, "echo", args("n", 1), CALL));
        long t1 = System.nanoTime();
        long second = pidOf(pool.callTool(spec, "echo", args("n", 2), CALL));
        long third = pidOf(pool.callTool(spec, "echo", args("n", 3), CALL));
        long t2 = System.nanoTime();

        System.out.printf("[MCP会话池] 3 次顺序调用 pid=%d,%d,%d  stats=%s；第1次(含启动+握手)=%dms，后2次共=%dms%n",
                first, second, third, pool.stats(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1, pool.stats().started());
        assertEquals(2, pool.stats().reused());
    }

    @Test
    void concurrentCalls_shareOneProcess_andEachGetsItsOwnAnswer() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<JSONObject>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                futures.add(executor.submit(() -> pool.callTool(spec, "echo", args("n", n), CALL)));
            }
            for (int i = 0; i < 16; i++) {
                String answer = text(futures.get(i).get());
                assertTrue(answer.contains("\"n\":" + i), "第 " + i + " 个请求拿到了别人的响应: " + answer);
            }
            assertEquals(1, pool.stats().started(), "16 个并发调用应该只启动 1 个进程");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentEnv_isADifferentProcess() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));

        long a = pidOf(pool.callTool(FakeMcpServer.spec(Map.of("API_KEY", "a")), "echo", args("n", 1), CALL));
        long b = pidOf(pool.callTool(FakeMcpServer.spec(Map.of("API_KEY", "b")), "echo", args("n", 1), CALL));

        assertNotEquals(a, b, "换了环境变量（比如密钥）就必须是新进程，不能把旧密钥的进程串用");
        assertEquals(2, pool.stats().started());
    }

    @Test
    void serverThatDiedBetweenCalls_isTransparentlyRestarted() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());

        long before = pidOf(pool.callTool(spec, "exit_after_reply", new JSONObject(), CALL));
        awaitDead(before);

        long after = pidOf(pool.callTool(spec, "echo", args("n", 1), CALL));

        assertNotEquals(before, after);
        assertTrue(alive(after));
        assertEquals(2, pool.stats().started());
    }

    @Test
    void crashDuringCall_surfacesAsError_isNotRetried_andPoolStaysUsable() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());
        pool.callTool(spec, "echo", args("n", 0), CALL);

        assertThrows(IOException.class, () -> pool.callTool(spec, "crash", new JSONObject(), CALL));
        assertEquals(1, pool.stats().started(), "请求已经发出后才崩溃，不能自动重发（工具可能已执行过一次）");

        String answer = text(pool.callTool(spec, "echo", args("n", 1), CALL));
        assertTrue(answer.contains("calls=1"), "新进程应该是全新的: " + answer);
        assertEquals(2, pool.stats().started());
    }

    @Test
    void timedOutCall_closesThatProcess_andNextCallUsesAFreshOne() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());
        long stuck = pidOf(pool.callTool(spec, "echo", args("n", 0), CALL));

        assertThrows(TimeoutException.class,
                () -> pool.callTool(spec, "slow", args("ms", 3000), Duration.ofMillis(300)));
        awaitDead(stuck);

        long fresh = pidOf(pool.callTool(spec, "echo", args("n", 1), CALL));
        assertNotEquals(stuck, fresh);
    }

    @Test
    void unknownTool_failsBeforeSendingAnything_andListsWhatExists() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());

        McpSessionPool.McpToolNotFoundException error = assertThrows(McpSessionPool.McpToolNotFoundException.class,
                () -> pool.callTool(spec, "web_serach", new JSONObject(), CALL));

        assertTrue(error.getMessage().contains("web_serach") && error.getMessage().contains("echo"), error.getMessage());
        String answer = text(pool.callTool(spec, "echo", new JSONObject(), CALL));
        assertTrue(answer.contains("calls=1"), "服务端只应收到这一次 tools/call，说明上一次没发出去: " + answer);
        assertEquals(1, pool.stats().started(), "校验失败不应毁掉会话");
    }

    @Test
    void listTools_returnsWhatTheServerDeclared() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));

        List<String> names = pool.listTools(FakeMcpServer.spec(Map.of())).stream().map(t -> t.getString("name")).toList();

        assertEquals(List.of("echo", "slow", "exit_after_reply", "crash"), names);
    }

    @Test
    void idleProcess_isReclaimed() throws Exception {
        McpSessionPool pool = pool(Duration.ofMillis(300));
        long pid = pidOf(pool.callTool(FakeMcpServer.spec(Map.of()), "echo", args("n", 1), CALL));
        assertTrue(alive(pid));

        awaitDead(pid);

        assertEquals(0, pool.stats().active());
    }

    @Test
    void closingThePool_killsEveryChildProcess() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        long a = pidOf(pool.callTool(FakeMcpServer.spec(Map.of("K", "a")), "echo", args("n", 1), CALL));
        long b = pidOf(pool.callTool(FakeMcpServer.spec(Map.of("K", "b")), "echo", args("n", 1), CALL));

        pool.close();

        assertFalse(alive(a));
        assertFalse(alive(b));
    }

    @Test
    void evict_dropsTheProcessOfAChangedConfig() throws Exception {
        McpSessionPool pool = pool(Duration.ofMinutes(10));
        McpServerSpec spec = FakeMcpServer.spec(Map.of());
        long pid = pidOf(pool.callTool(spec, "echo", args("n", 1), CALL));

        pool.evict(spec);

        awaitDead(pid);
        assertEquals(0, pool.stats().active());
    }

    private static void awaitDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (alive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(alive(pid), "进程 " + pid + " 应该已经退出");
    }
}
