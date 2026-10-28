package com.paiagent.engine.agent;

import org.springframework.ai.model.function.FunctionCallback;

/**
 * 把 {@link AgentTool} 声明给模型服务商的 {@code tools} 参数（名称、描述、参数 JSON Schema）。
 *
 * <p>这个类只负责“声明”。执行由 ReAct 节点自己的循环完成（请求里带 {@code proxyToolCalls=true}，
 * Spring AI 不会代为调用），所以 {@link #call(String)} 被调到就是接线出了错，直接报错而不是静默执行。
 */
public class NativeToolCallback implements FunctionCallback {

    private final AgentTool tool;

    public NativeToolCallback(AgentTool tool) {
        this.tool = tool;
    }

    @Override
    public String getName() {
        return tool.getName();
    }

    @Override
    public String getDescription() {
        return tool.getDescription();
    }

    @Override
    public String getInputTypeSchema() {
        return tool.getInputSchema();
    }

    @Override
    public String call(String functionInput) {
        throw new UnsupportedOperationException(
                "工具 " + tool.getName() + " 由 ReAct 节点的循环执行，不应由 Spring AI 内部调用");
    }
}
