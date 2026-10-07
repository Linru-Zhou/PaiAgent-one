package com.paiagent.engine.langgraph.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 状态管理器：负责 LangGraph 状态 Map 的初始化与读取。
 * 状态里只放运行元信息（输入、状态、当前节点、最近一次输出）；节点输出这类需要精确合并的数据在 LangGraphRunContext 里。
 */
@Slf4j
@Component
public class StateManager {

    public Map<String, Object> initializeState(String inputData) {
        Map<String, Object> state = new HashMap<>();
        state.put("inputData", inputData);

        Map<String, Object> currentInput = new HashMap<>();
        currentInput.put("input", inputData);
        state.put("currentInput", currentInput);

        state.put("status", "RUNNING");
        state.put("startTime", System.currentTimeMillis());
        return state;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getFinalOutput(Map<String, Object> state) {
        return (Map<String, Object>) state.getOrDefault("currentInput", new HashMap<>());
    }

    public boolean isSuccessful(Map<String, Object> state) {
        String status = (String) state.getOrDefault("status", "RUNNING");
        return !"FAILED".equals(status);
    }

    public String getErrorMessage(Map<String, Object> state) {
        return (String) state.get("errorMessage");
    }
}
