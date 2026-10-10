package com.paiagent.engine.langgraph;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止依赖回退：pom 里曾经同时声明了 langgraph4j-core-jdk8:1.1.5 和 1.8.0-beta3 的 core，两个 jar 都含有
 * org.bsc.langgraph4j.StateGraph，排在前面的旧版本胜出，项目实际一直跑在 1.1.5 上（没有并行分支），
 * 还因此误以为"LangGraph 做不了扇出"。这里断言真正加载的是 1.8.0-beta3，并且原生扇出汇合可用。
 */
class LangGraphDependencyTest {

    private static AsyncNodeAction<AgentState> sleeper(long ms) {
        return state -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return Map.<String, Object>of("ok", true);
        });
    }

    @Test
    void stateGraphComesFromTheDeclaredVersion() {
        String location = StateGraph.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location.contains("langgraph4j-core-1.8.0-beta3"), "实际加载的 StateGraph 来自: " + location);
    }

    @Test
    void nativeFanOutJoinRunsInParallel() throws Exception {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        graph.addNode("a", sleeper(5));
        graph.addNode("b", sleeper(400));
        graph.addNode("c", sleeper(400));
        graph.addNode("d", sleeper(5));
        graph.addEdge(StateGraph.START, "a");
        graph.addEdge("a", "b");
        graph.addEdge("a", "c");
        graph.addEdge("b", "d");
        graph.addEdge("c", "d");
        graph.addEdge("d", StateGraph.END);

        long start = System.currentTimeMillis();
        graph.compile().invoke(Map.of("input", "x"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 750, "两个 400ms 的分支并行应约 400ms，实际 " + elapsed + "ms");
        assertEquals(true, elapsed >= 400);
    }
}
