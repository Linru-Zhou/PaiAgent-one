package com.paiagent.engine.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 给任意 ChatModel 套上"按供应商限额"：所有经过 ChatClientFactory 创建的客户端（普通节点、ReAct、视觉分析）
 * 都会自动受限，不需要在每个调用点各写一遍。
 */
public class GuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String provider;
    private final LlmConcurrencyLimiter limiter;
    private final Duration streamIdleTimeout;

    public GuardedChatModel(ChatModel delegate, String provider, LlmConcurrencyLimiter limiter, Duration streamIdleTimeout) {
        this.delegate = delegate;
        this.provider = provider;
        this.limiter = limiter;
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return limiter.execute(provider, () -> delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 流式调用的名额要持有到流结束（完成/出错/取消）才释放
        return Flux.defer(() -> {
            Runnable release = limiter.acquire(provider);
            return delegate.stream(prompt)
                    .timeout(streamIdleTimeout)
                    .doFinally(signal -> release.run());
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
