package com.paiagent.engine.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Locale;

/**
 * ChatClient动态工厂
 * 根据节点配置在运行时创建不同类型的ChatClient实例
 */
@Slf4j
@Component
public class ChatClientFactory {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/v1/chat/completions";

    private static final String V1_SUFFIX = "/v1";

    /** 超时/重试策略与按供应商限流；Spring 容器会注入配置好的实例，手工 new 时（单测）使用默认值 */
    private LlmCallPolicy policy = LlmCallPolicy.defaults();
    private LlmConcurrencyLimiter limiter = LlmConcurrencyLimiter.defaults();

    @Autowired(required = false)
    public void setPolicy(LlmCallPolicy policy) {
        this.policy = policy;
    }

    @Autowired(required = false)
    public void setLimiter(LlmConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * 根据提供商和配置创建ChatClient
     *
     * @param provider    提供商标识 (openai/deepseek/qwen/step/zhipu/ai_ping/apifree)
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @return ChatClient实例
     */
    public ChatClient createClient(String provider, String apiUrl, String apiKey,
                                   String model, Double temperature) {
        return createClientWithFunctions(provider, apiUrl, apiKey, model, temperature, List.of());
    }

    /**
     * 创建带 Function Calling 支持的 ChatClient
     *
     * @param provider    提供商标识 (openai/deepseek/qwen/step/zhipu/ai_ping/apifree)
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @param functions   函数回调列表
     * @return ChatClient实例
     */
    public ChatClient createClientWithFunctions(String provider, String apiUrl, String apiKey,
                                                 String model, Double temperature,
                                                 List<FunctionCallback> functions) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedModel = normalizeModel(normalizedProvider, model);
        if (!normalizedModel.equals(model)) {
            log.warn("检测到 {} 模型别名，已自动归一化: {} -> {}", normalizedProvider, model, normalizedModel);
        }
        log.info("创建ChatClient - 类型: {}, URL: {}, 模型: {}, 温度: {}, 函数数量: {}",
                normalizedProvider, apiUrl, normalizedModel, temperature, functions.size());

        ChatModel chatModel = switch (normalizedProvider) {
            case "openai", "deepseek", "qwen", "step", "zhipu", "ai_ping" ->
                    createOpenAICompatibleModel(apiUrl, apiKey, normalizedModel, temperature);
            case "apifree" ->
                    createApifreeChatModel(apiUrl, apiKey, normalizedModel, temperature);
            case "volcengine_agent_plan" ->
                    createVolcengineArkChatModel(apiUrl, apiKey, normalizedModel, temperature);
            default -> throw new IllegalArgumentException("不支持的提供商类型: " + provider);
        };
        chatModel = new GuardedChatModel(chatModel, normalizedProvider, limiter, policy.readTimeout());

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 注册函数 - 直接传入 FunctionCallback 实例
        if (functions != null && !functions.isEmpty()) {
            builder.defaultFunctions(functions.toArray(new FunctionCallback[0]));
            for (FunctionCallback function : functions) {
                log.debug("注册函数: {}", function.getName());
            }
        }

        return builder.build();
    }

    /**
     * 创建OpenAI兼容的ChatModel
     * 支持OpenAI、DeepSeek和通义千问（通过OpenAI兼容接口）
     */
    private ChatModel createOpenAICompatibleModel(String apiUrl, String apiKey,
                                                   String model, Double temperature) {
        String normalizedApiUrl = normalizeBaseUrl(apiUrl);
        if (!normalizedApiUrl.equals(apiUrl)) {
            log.warn("检测到 OpenAI 兼容接口地址包含路径后缀，已自动归一化: {} -> {}", apiUrl, normalizedApiUrl);
        }

        // 带超时的 HTTP 客户端 + 自定义重试策略（Spring AI 默认无读超时、重试 10 次且退避最长 3 分钟）
        OpenAiApi openAiApi = new OpenAiApi(
                normalizedApiUrl,
                apiKey,
                policy.restClientBuilder(),
                policy.webClientBuilder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options, null, policy.retryTemplate());
    }

    /**
     * APIFree 的 SkyClaw agent endpoint 有时会以 text/plain 返回 OpenAI 兼容 JSON。
     * Spring AI 默认 JSON converter 不接收 text/plain，需要为 APIFree 单独放宽响应类型。
     */
    private ChatModel createApifreeChatModel(String apiUrl, String apiKey,
                                             String model, Double temperature) {
        String normalizedApiUrl = normalizeBaseUrl(apiUrl);
        if (!normalizedApiUrl.equals(apiUrl)) {
            log.warn("检测到 APIFree 兼容接口地址包含路径后缀，已自动归一化: {} -> {}", apiUrl, normalizedApiUrl);
        }

        OpenAiApi openAiApi = new OpenAiApi(
                normalizedApiUrl,
                apiKey,
                createTextPlainJsonRestClientBuilder(),
                policy.webClientBuilder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options, null, policy.retryTemplate());
    }

    /**
     * Agent Plan 的 OpenAI 兼容 Chat 接口路径是 /api/plan/v3/chat/completions。
     * Spring AI 默认会拼 /v1/chat/completions，这里显式覆盖为 Agent Plan 的路径。
     */
    private ChatModel createVolcengineArkChatModel(String apiUrl, String apiKey,
                                                    String model, Double temperature) {
        String baseUrl = normalizeAgentPlanBaseUrl(apiUrl);
        if (!baseUrl.equals(apiUrl)) {
            log.warn("检测到 Agent Plan 配置地址包含接口路径，已自动归一化: {} -> {}{}", apiUrl, baseUrl, "/api/plan/v3/chat/completions");
        }

        OpenAiApi openAiApi = new OpenAiApi(
                baseUrl,
                apiKey,
                "/api/plan/v3/chat/completions",
                "/api/plan/v3/embeddings",
                policy.restClientBuilder(),
                policy.webClientBuilder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER
        );

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options, null, policy.retryTemplate());
    }

    /**
     * Spring AI 的 OpenAiApi 会自行拼接 /v1/chat/completions，
     * 这里统一将用户输入的 URL 归一化为服务根地址，兼容误填完整接口地址的情况。
     */
    private String normalizeBaseUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        normalized = stripSuffixIgnoreCase(normalized, CHAT_COMPLETIONS_SUFFIX);
        normalized = stripSuffixIgnoreCase(normalized, V1_SUFFIX);
        return stripTrailingSlash(normalized);
    }

    private String normalizeAgentPlanBaseUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        normalized = stripSuffixIgnoreCase(normalized, "/api/plan/v3/chat/completions");
        normalized = stripSuffixIgnoreCase(normalized, "/api/plan/v3");
        return stripTrailingSlash(normalized);
    }

    private String stripSuffixIgnoreCase(String value, String suffix) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (!lowerValue.endsWith(suffix)) {
            return value;
        }
        return value.substring(0, value.length() - suffix.length());
    }

    private String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private RestClient.Builder createTextPlainJsonRestClientBuilder() {
        MappingJackson2HttpMessageConverter textPlainJsonConverter = new MappingJackson2HttpMessageConverter();
        textPlainJsonConverter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("application", "*+json"),
                MediaType.TEXT_PLAIN
        ));

        return policy.restClientBuilder()
                .messageConverters(converters -> converters.add(0, textPlainJsonConverter));
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            return "";
        }

        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "open ai" -> "openai";
            case "deep seek" -> "deepseek";
            case "通义千问" -> "qwen";
            case "stepfun", "阶跃星辰" -> "step";
            case "智谱" -> "zhipu";
            case "ai ping" -> "ai_ping";
            case "api free", "apifree.ai", "skyclaw", "skyclaw-v1", "skyclaw-v1.0",
                 "skyclaw-v1-lite", "skyclaw-v1.0-lite", "skywork-ai/skyclaw-v1",
                 "skywork-ai/skyclaw-v1-lite" -> "apifree";
            case "volcengine", "ark", "agent_plan", "agent plan", "火山方舟" -> "volcengine_agent_plan";
            default -> normalized;
        };
    }

    private String normalizeModel(String provider, String model) {
        if (model == null) {
            return "";
        }

        if (!"apifree".equals(provider)) {
            return model;
        }

        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "skyclaw", "skyclaw-v1", "skyclaw-v1.0" -> "skywork-ai/skyclaw-v1";
            case "skyclaw-lite", "skyclaw-v1-lite", "skyclaw-v1.0-lite" -> "skywork-ai/skyclaw-v1-lite";
            default -> model.trim();
        };
    }
}
