package com.paiagent.engine.executor.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import com.paiagent.service.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Slf4j
@Component
public class TTSNodeExecutor implements NodeExecutor {
    
    private static final int QWEN_MAX_TTS_INPUT_LENGTH = 400;
    private static final int QWEN_MAX_TTS_INPUT_BYTES = 600;
    private static final int STEP_MAX_TTS_INPUT_LENGTH = 1000;
    private static final int STEP_INSTRUCTION_MAX_LENGTH = 200;
    private static final Object DASHSCOPE_HTTP_URL_LOCK = new Object();
    
    @Autowired
    private MinioService minioService;

    @Autowired
    private LLMGlobalConfigService llmGlobalConfigService;
    
    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }
    
    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input, Consumer<ExecutionEvent> progressCallback) throws Exception {
        String text = extractInputText(node, input);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("输入文本不能为空");
        }
        
        Map<String, Object> data = node.getData();
        TtsRuntimeConfig config = resolveConfig(data);
        
        validateConfig(config);
        
        log.info("TTS 节点执行 - 供应商: {}, 模型: {}, 文本长度: {}, 音色: {}, 语言类型: {}",
                config.provider(), config.model(), text.length(), config.voice(), config.languageType());
        
        List<String> textChunks = splitText(text, config.maxInputLength(), config.maxInputBytes());
        log.info("文本分割为 {} 个片段", textChunks.size());
        
        if (progressCallback != null) {
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("totalChunks", textChunks.size());
            progressData.put("currentChunk", 0);
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(), 
                node.getType(), 
                "文本已分割为 " + textChunks.size() + " 个片段", 
                progressData
            ));
        }
        
        List<byte[]> audioChunks = new ArrayList<>();
        List<CompletableFuture<byte[]>> futures = new ArrayList<>();
        
        for (int i = 0; i < textChunks.size(); i++) {
            final int chunkIndex = i;
            final String chunk = textChunks.get(i);
            
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    int utf8ByteLength = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    log.info("处理第 {}/{} 个片段, 字符数: {}, UTF-8 字节数: {}", 
                            chunkIndex + 1, textChunks.size(), chunk.length(), utf8ByteLength);
                    
                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressData.put("chunkText", chunk.substring(0, Math.min(50, chunk.length())) + "...");
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                            node.getId(), 
                            node.getType(), 
                            "正在处理第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段", 
                            progressData
                        ));
                    }
                    
                    byte[] audioData = synthesizeChunk(config, chunk, chunkIndex + 1, textChunks.size());
                    
                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressData.put("completedChunks", chunkIndex + 1);
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                            node.getId(), 
                            node.getType(), 
                            "已完成第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段", 
                            progressData
                        ));
                    }
                    
                    return audioData;
                } catch (Exception e) {
                    throw new RuntimeException("处理第 " + (chunkIndex + 1) + " 个片段失败: " + e.getMessage(), e);
                }
            });
            
            futures.add(future);
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        for (CompletableFuture<byte[]> future : futures) {
            audioChunks.add(future.get());
        }
        
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(), 
                node.getType(), 
                "正在合并 " + audioChunks.size() + " 个音频片段...", 
                null
            ));
        }
        
        byte[] mergedAudio = mergeWavFiles(audioChunks);
        
        String fileName = "audio_" + UUID.randomUUID() + ".wav";
        String objectName = "audio/" + fileName;
        String minioUrl = minioService.uploadFromBytes(mergedAudio, objectName, "audio/wav");
        
        Map<String, Object> output = new HashMap<>();
        output.put("audioUrl", minioUrl);
        output.put("fileName", fileName);
        output.put("output", minioUrl);
        output.put("chunks", textChunks.size());
        
        log.info("TTS 合并音频已上传到 MinIO: {}, 共 {} 个片段", minioUrl, textChunks.size());
        
        return output;
    }

    private TtsRuntimeConfig resolveConfig(Map<String, Object> data) {
        Long configId = parseLong(data.get("configId"));
        String provider = null;
        String apiUrl = null;
        String apiKey = null;
        String model = null;
        boolean explicitTtsModel = false;

        if (configId != null) {
            LLMGlobalConfig globalConfig = llmGlobalConfigService.getById(configId);
            if (globalConfig != null) {
                provider = canonicalizeProvider(globalConfig.getProvider());
                apiUrl = trimString(globalConfig.getApiUrl());
                apiKey = trimString(globalConfig.getApiKey());
                model = trimString(globalConfig.getTtsModel());
                explicitTtsModel = StringUtils.hasText(model);
                if (!explicitTtsModel) {
                    model = trimString(globalConfig.getModel());
                }
                log.info("TTS 使用全局配置: {}", globalConfig.getConfigName());
            } else {
                log.warn("TTS 全局配置不存在: {}", configId);
            }
        }

        if (!StringUtils.hasText(apiKey)) {
            apiKey = trimString(data.get("apiKey"));
        }
        if (!StringUtils.hasText(apiUrl)) {
            apiUrl = trimString(data.get("apiUrl"));
        }
        if (!StringUtils.hasText(model)) {
            model = trimString(data.get("model"));
        }
        if (!StringUtils.hasText(provider)) {
            provider = canonicalizeProvider(trimString(data.get("provider")));
        }
        if (!StringUtils.hasText(provider)) {
            provider = inferProviderFromModel(model);
        }

        String voice = trimString(data.get("voice"));
        if (!StringUtils.hasText(voice)) {
            voice = "step".equals(provider) ? "cixingnansheng" : "Cherry";
        }

        String languageType = trimString(data.get("languageType"));
        if (!StringUtils.hasText(languageType)) {
            languageType = "Auto";
        }

        String instruction = trimString(data.get("instruction"));
        double speed = parseDouble(data.get("speed"), 1.0);
        double volume = parseDouble(data.get("volume"), 1.0);
        int sampleRate = parseInt(data.get("sampleRate"), 24000);

        model = normalizeTtsModel(provider, model, explicitTtsModel);
        if ("qwen".equals(provider) && QwenAudioTtsSupport.isQwenAudioModel(model)) {
            // Qwen-Audio 系列的音色是 xxx_v3.1 这类字符串 ID；节点里残留的 Cherry 等 qwen3-tts 音色不能用
            String resolvedVoice = QwenAudioTtsSupport.resolveVoice(model, voice);
            if (!resolvedVoice.equals(voice)) {
                log.warn("Qwen-Audio 模型 {} 不支持音色 [{}]，已改用 {}", model, voice, resolvedVoice);
            }
            voice = resolvedVoice;
        }

        return new TtsRuntimeConfig(
                provider,
                apiUrl,
                apiKey,
                model,
                voice,
                languageType,
                instruction,
                speed,
                volume,
                sampleRate
        );
    }

    private void validateConfig(TtsRuntimeConfig config) {
        if (!StringUtils.hasText(config.provider())) {
            throw new IllegalArgumentException("TTS 节点缺少供应商配置，请选择全局配置或手动选择供应商");
        }
        if (!StringUtils.hasText(config.apiKey())) {
            throw new IllegalArgumentException("TTS API Key 不能为空，请选择全局配置或在节点配置中设置");
        }
        if (!StringUtils.hasText(config.model())) {
            throw new IllegalArgumentException("TTS 模型名称不能为空");
        }
        if (!"qwen".equals(config.provider()) && !"step".equals(config.provider())) {
            throw new IllegalArgumentException("TTS 暂不支持的供应商: " + config.provider());
        }
        if ("step".equals(config.provider()) && config.instruction() != null
                && config.instruction().length() > STEP_INSTRUCTION_MAX_LENGTH) {
            throw new IllegalArgumentException("StepAudio 2.5 TTS 的 instruction 不能超过 200 字符");
        }
    }

    private byte[] synthesizeChunk(TtsRuntimeConfig config, String chunk, int chunkIndex, int totalChunks) throws Exception {
        return switch (config.provider()) {
            case "step" -> callStepTts(config, chunk);
            case "qwen" -> QwenAudioTtsSupport.isQwenAudioModel(config.model())
                    ? callQwenAudioTts(config, chunk, chunkIndex, totalChunks)
                    : callQwenTts(config, chunk, chunkIndex, totalChunks);
            default -> throw new IllegalArgumentException("TTS 暂不支持的供应商: " + config.provider());
        };
    }

    private byte[] callQwenTts(TtsRuntimeConfig config, String chunk, int chunkIndex, int totalChunks) throws Exception {
        AudioParameters.Voice voice = convertVoice(config.voice());
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(config.apiKey())
                .model(config.model())
                .text(chunk)
                .voice(voice)
                .languageType(config.languageType())
                .build();

        MultiModalConversationResult result;
        synchronized (DASHSCOPE_HTTP_URL_LOCK) {
            String previousBaseUrl = Constants.baseHttpApiUrl;
            try {
                String baseUrl = normalizeDashScopeBaseApiUrl(config.apiUrl());
                if (StringUtils.hasText(baseUrl)) {
                    Constants.baseHttpApiUrl = baseUrl;
                }
                result = new MultiModalConversation().call(param);
            } finally {
                Constants.baseHttpApiUrl = previousBaseUrl;
            }
        }

        String audioUrl = result.getOutput().getAudio().getUrl();
        if (!StringUtils.hasText(audioUrl)) {
            throw new RuntimeException("阿里百炼 TTS 返回的音频URL为空 (片段 " + chunkIndex + ")");
        }

        log.info("第 {}/{} 个片段音频URL: {}", chunkIndex, totalChunks, audioUrl);
        return downloadAudio(audioUrl);
    }

    /**
     * Qwen-Audio 系列（qwen-audio-3.x-tts-flash）：WebSocket 的 ttsv2.SpeechSynthesizer，同步模式（回调传 null），
     * 直接返回音频字节。DashScope SDK 的服务地址是个全局静态变量，沿用 qwen3-tts 路径的做法：加锁设置、用完还原。
     */
    private byte[] callQwenAudioTts(TtsRuntimeConfig config, String chunk, int chunkIndex, int totalChunks) throws Exception {
        int sampleRate = QwenAudioTtsSupport.sampleRateOf(config.sampleRate());
        SpeechSynthesisParam.SpeechSynthesisParamBuilder<?, ?> builder = SpeechSynthesisParam.builder()
                .apiKey(config.apiKey())
                .model(config.model())
                .voice(config.voice())
                .format(QwenAudioTtsSupport.wavFormat(sampleRate));
        if (config.speed() >= 0.5 && config.speed() <= 2.0 && config.speed() != 1.0) {
            builder.speechRate((float) config.speed());
        }
        SpeechSynthesisParam param = builder.build();

        byte[] audio;
        synchronized (DASHSCOPE_HTTP_URL_LOCK) {
            String previousWsUrl = Constants.baseWebsocketApiUrl;
            SpeechSynthesizer synthesizer = null;
            try {
                String wsUrl = QwenAudioTtsSupport.normalizeWebsocketUrl(config.apiUrl());
                if (StringUtils.hasText(wsUrl)) {
                    Constants.baseWebsocketApiUrl = wsUrl;
                }
                synthesizer = new SpeechSynthesizer(param, null);
                ByteBuffer buffer = synthesizer.call(chunk);
                if (buffer == null || !buffer.hasRemaining()) {
                    throw new RuntimeException("Qwen-Audio TTS 返回的音频为空 (片段 " + chunkIndex + ")");
                }
                audio = new byte[buffer.remaining()];
                buffer.get(audio);
            } finally {
                if (synthesizer != null) {
                    try {
                        synthesizer.getDuplexApi().close(1000, "bye");
                    } catch (Exception e) {
                        log.debug("关闭 TTS WebSocket 连接失败: {}", e.getMessage());
                    }
                }
                Constants.baseWebsocketApiUrl = previousWsUrl;
            }
        }
        log.info("第 {}/{} 个片段合成完成（Qwen-Audio），{} 字节", chunkIndex, totalChunks, audio.length);
        return QwenAudioTtsSupport.ensureWav(audio, sampleRate);
    }

    private byte[] callStepTts(TtsRuntimeConfig config, String chunk) throws Exception {
        String endpoint = normalizeStepSpeechEndpoint(config.apiUrl());
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "audio/wav,application/json");

        JSONObject body = new JSONObject();
        body.put("model", config.model());
        body.put("input", chunk);
        body.put("voice", config.voice());
        body.put("response_format", "wav");
        body.put("speed", config.speed());
        body.put("volume", config.volume());
        body.put("sample_rate", config.sampleRate());
        if (StringUtils.hasText(config.instruction())) {
            body.put("instruction", config.instruction());
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        try (InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (is != null) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
            }
            byte[] responseBytes = baos.toByteArray();
            if (status < 200 || status >= 300) {
                String responseText = new String(responseBytes, StandardCharsets.UTF_8);
                throw new RuntimeException("StepAudio TTS 调用失败, HTTP " + status + ": " + responseText);
            }
            if (responseBytes.length == 0) {
                throw new RuntimeException("StepAudio TTS 返回的音频为空");
            }
            return responseBytes;
        } finally {
            conn.disconnect();
        }
    }
    
    private AudioParameters.Voice convertVoice(String voiceStr) {
        try {
            return AudioParameters.Voice.valueOf(voiceStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知音色: {}, 使用默认音色 CHERRY", voiceStr);
            return AudioParameters.Voice.CHERRY;
        }
    }
    
    private String extractInputText(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> data = node.getData();
        List<Map<String, Object>> inputParams = (List<Map<String, Object>>) data.get("inputParams");
        
        if (inputParams != null && !inputParams.isEmpty()) {
            for (Map<String, Object> param : inputParams) {
                String paramName = (String) param.get("name");
                if ("text".equals(paramName)) {
                    String type = (String) param.get("type");
                    if ("input".equals(type)) {
                        return (String) param.get("value");
                    } else if ("reference".equals(type)) {
                        String referenceNode = (String) param.get("referenceNode");
                        if (StringUtils.hasText(referenceNode)) {
                            String[] parts = referenceNode.split("\\.");
                            if (parts.length == 2) {
                                String paramKey = parts[1];
                                Object value = input.get(paramKey);
                                if (value instanceof String) {
                                    return (String) value;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        String text = (String) input.get("output");
        if (StringUtils.hasText(text)) {
            return text;
        }
        
        text = (String) input.get("input");
        if (StringUtils.hasText(text)) {
            return text;
        }
        
        return (String) input.get("text");
    }
    
    @Override
    public String getSupportedNodeType() {
        return "tts";
    }
    
    private List<String> splitText(String text, int maxLength, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());
            
            while (end > start) {
                String candidate = text.substring(start, end);
                int byteLength = candidate.getBytes(StandardCharsets.UTF_8).length;
                
                if (byteLength <= maxBytes) {
                    if (end < text.length()) {
                        int lastPunctuation = findLastPunctuation(text, start, end);
                        if (lastPunctuation > start) {
                            end = lastPunctuation + 1;
                            candidate = text.substring(start, end);
                        }
                    }
                    
                    chunks.add(candidate);
                    start = end;
                    break;
                }
                
                end -= 10;
            }
            
            if (end <= start) {
                end = start + 1;
                while (end <= text.length()) {
                    String candidate = text.substring(start, end);
                    int byteLength = candidate.getBytes(StandardCharsets.UTF_8).length;
                    if (byteLength > maxBytes) {
                        if (end - 1 > start) {
                            chunks.add(text.substring(start, end - 1));
                            start = end - 1;
                        } else {
                            throw new IllegalArgumentException("单个字符超过 600 字节,无法处理");
                        }
                        break;
                    }
                    end++;
                }
            }
        }
        
        return chunks;
    }
    
    private int findLastPunctuation(String text, int start, int end) {
        String punctuations = "。！？；,.!?;";
        for (int i = end - 1; i >= start; i--) {
            if (punctuations.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeDashScopeBaseApiUrl(String apiUrl) {
        if (!StringUtils.hasText(apiUrl)) {
            return null;
        }
        String normalized = apiUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = normalized
                .replace("/services/aigc/multimodal-generation/generation", "")
                .replace("/compatible-mode/v1", "/api/v1")
                .replace("/compatible-mode", "/api/v1");
        if (!normalized.endsWith("/api/v1")) {
            normalized = normalized + "/api/v1";
        }
        return normalized;
    }

    private String normalizeStepSpeechEndpoint(String apiUrl) {
        String normalized = StringUtils.hasText(apiUrl) ? apiUrl.trim() : "https://api.stepfun.com/v1";
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/audio/speech")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/audio/speech";
        }
        if (normalized.endsWith("/step_plan/v1")) {
            return normalized + "/audio/speech";
        }
        return normalized + "/v1/audio/speech";
    }

    private String canonicalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return null;
        }
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "stepfun", "阶跃星辰" -> "step";
            case "通义千问", "dashscope", "aliyun" -> "qwen";
            default -> provider.trim().toLowerCase(Locale.ROOT);
        };
    }

    private String inferProviderFromModel(String model) {
        if (!StringUtils.hasText(model)) {
            return "qwen";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("step") || normalized.contains("stepaudio")) {
            return "step";
        }
        return "qwen";
    }

    private String normalizeTtsModel(String provider, String model, boolean explicitTtsModel) {
        String normalizedProvider = canonicalizeProvider(provider);
        String trimmedModel = trimString(model);
        if (explicitTtsModel) {
            return trimmedModel;
        }
        String lowerModel = trimmedModel == null ? "" : trimmedModel.toLowerCase(Locale.ROOT);

        if ("step".equals(normalizedProvider)) {
            if (lowerModel.contains("step") && lowerModel.contains("tts")) {
                return trimmedModel;
            }
            if (StringUtils.hasText(trimmedModel)) {
                log.warn("StepAudio TTS 收到非 TTS 模型配置: {}, 已切换为 stepaudio-2.5-tts", trimmedModel);
            }
            return "stepaudio-2.5-tts";
        }

        if ("qwen".equals(normalizedProvider)) {
            if (lowerModel.contains("tts")) {
                return trimmedModel;
            }
            if (StringUtils.hasText(trimmedModel)) {
                log.warn("Qwen TTS 收到非 TTS 模型配置: {}, 已切换为 qwen3-tts-flash", trimmedModel);
            }
            return "qwen3-tts-flash";
        }

        return trimmedModel;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            return Long.parseLong(string);
        }
        return null;
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            return Double.parseDouble(string);
        }
        return defaultValue;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            return Integer.parseInt(string);
        }
        return defaultValue;
    }

    private String trimString(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }
    
    private byte[] downloadAudio(String audioUrl) throws Exception {
        URL url = new URL(audioUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
    
    private byte[] mergeWavFiles(List<byte[]> audioChunks) throws Exception {
        if (audioChunks.isEmpty()) {
            throw new IllegalArgumentException("音频片段列表为空");
        }
        
        if (audioChunks.size() == 1) {
            return audioChunks.get(0);
        }
        
        byte[] firstChunk = audioChunks.get(0);
        if (firstChunk.length < 44) {
            throw new IllegalArgumentException("无效的 WAV 文件格式");
        }
        
        ByteArrayOutputStream mergedStream = new ByteArrayOutputStream();
        
        byte[] header = Arrays.copyOf(firstChunk, 44);
        mergedStream.write(header);
        
        for (byte[] chunk : audioChunks) {
            if (chunk.length > 44) {
                mergedStream.write(chunk, 44, chunk.length - 44);
            }
        }
        
        byte[] mergedData = mergedStream.toByteArray();
        
        int dataSize = mergedData.length - 44;
        int fileSize = mergedData.length - 8;
        
        mergedData[4] = (byte) (fileSize & 0xFF);
        mergedData[5] = (byte) ((fileSize >> 8) & 0xFF);
        mergedData[6] = (byte) ((fileSize >> 16) & 0xFF);
        mergedData[7] = (byte) ((fileSize >> 24) & 0xFF);
        
        mergedData[40] = (byte) (dataSize & 0xFF);
        mergedData[41] = (byte) ((dataSize >> 8) & 0xFF);
        mergedData[42] = (byte) ((dataSize >> 16) & 0xFF);
        mergedData[43] = (byte) ((dataSize >> 24) & 0xFF);
        
        return mergedData;
    }

    private record TtsRuntimeConfig(
            String provider,
            String apiUrl,
            String apiKey,
            String model,
            String voice,
            String languageType,
            String instruction,
            double speed,
            double volume,
            int sampleRate
    ) {
        int maxInputLength() {
            if ("step".equals(provider)) {
                return STEP_MAX_TTS_INPUT_LENGTH;
            }
            return QwenAudioTtsSupport.isQwenAudioModel(model) ? QwenAudioTtsSupport.MAX_INPUT_LENGTH : QWEN_MAX_TTS_INPUT_LENGTH;
        }

        int maxInputBytes() {
            if ("step".equals(provider)) {
                return Integer.MAX_VALUE;
            }
            return QwenAudioTtsSupport.isQwenAudioModel(model) ? QwenAudioTtsSupport.MAX_INPUT_BYTES : QWEN_MAX_TTS_INPUT_BYTES;
        }
    }
}
