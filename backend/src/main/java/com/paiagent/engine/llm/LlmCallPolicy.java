package com.paiagent.engine.llm;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient.Version;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * 大模型调用的超时与重试策略。
 *
 * Spring AI 1.0.0-M5 自带的默认策略有两个问题：没有读超时（对端挂住就一直等），
 * 默认重试 10 次、退避从 2 秒起每次乘 5、最长 3 分钟，持续 429/5xx 时一个节点能卡上十几分钟。
 * 这里改成：连接/读超时可配置，最多 3 次尝试、1s 起指数退避（上限 8s），
 * 且只重试"供应商明确告知的临时错误"和连接层故障，读超时不重试（等了这么久再重试只会让延迟翻倍）。
 */
@Component
public class LlmCallPolicy {

    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public LlmCallPolicy(@Value("${paiagent.llm.connect-timeout-ms:10000}") long connectTimeoutMs,
                         @Value("${paiagent.llm.read-timeout-ms:120000}") long readTimeoutMs,
                         @Value("${paiagent.llm.max-attempts:3}") int maxAttempts,
                         @Value("${paiagent.llm.retry-initial-backoff-ms:1000}") long initialBackoffMs,
                         @Value("${paiagent.llm.retry-max-backoff-ms:8000}") long maxBackoffMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
    }

    public static LlmCallPolicy defaults() {
        return new LlmCallPolicy(10_000, 120_000, 3, 1_000, 8_000);
    }

    public JdkClientHttpRequestFactory requestFactory() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }

    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(requestFactory());
    }

    /** 本项目没有引入 reactor-netty，WebClient 走 JDK HttpClient；这里只能设置连接超时，流式的空闲超时由 GuardedChatModel 负责。 */
    public WebClient.Builder webClientBuilder() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        return WebClient.builder().clientConnector(new JdkClientHttpConnector(client));
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }

    public RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new TransientOnlyRetryPolicy(maxAttempts));
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(initialBackoffMs);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(maxBackoffMs);
        template.setBackOffPolicy(backOff);
        return template;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 只重试临时性错误：供应商返回 429/5xx（TransientAiException）或连接层故障；读超时和 4xx 不重试。 */
    static class TransientOnlyRetryPolicy extends SimpleRetryPolicy {

        TransientOnlyRetryPolicy(int maxAttempts) {
            super(maxAttempts);
        }

        @Override
        public boolean canRetry(RetryContext context) {
            Throwable last = context.getLastThrowable();
            if (last != null && !isTransient(last)) {
                return false;
            }
            return context.getRetryCount() < getMaxAttempts();
        }

        static boolean isTransient(Throwable throwable) {
            if (throwable instanceof TransientAiException) {
                return true;
            }
            if (throwable instanceof ResourceAccessException) {
                for (Throwable t = throwable; t != null; t = t.getCause()) {
                    if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
    }
}
