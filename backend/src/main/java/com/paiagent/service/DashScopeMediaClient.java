package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通义（阿里云百炼 / DashScope）的生图与生视频：通义万相。
 *
 * 两者都是异步任务：POST 提交（必须带 X-DashScope-Async: enable）→ 返回 task_id → GET /tasks/{id} 轮询
 * （状态 PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / UNKNOWN）→ 成功后从 output 里取结果地址（24 小时有效）。
 * 与配音、对话共用同一个百炼 API Key 和同一个地址（https://dashscope.aliyuncs.com/api/v1）。
 *
 * 取消：POST /tasks/{id}/cancel，官方说明只有 PENDING（排队中）的任务可以取消，运行中的会被拒绝。
 */
@Slf4j
@Service
public class DashScopeMediaClient implements VideoTaskClient {

    static final String DEFAULT_IMAGE_MODEL = "wan2.2-t2i-flash";
    static final String DEFAULT_VIDEO_MODEL = "wan2.2-t2v-plus";
    static final String IMAGE_SYNTHESIS_PATH = "/services/aigc/text2image/image-synthesis";
    static final String VIDEO_SYNTHESIS_PATH = "/services/aigc/video-generation/video-synthesis";

    private static final Pattern WXH = Pattern.compile("(\\d{3,4})\\s*[x*×]\\s*(\\d{3,4})", Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long pollIntervalMs = 3_000;
    private int maxImagePolls = 100; // 约 5 分钟

    /** 测试用：缩短轮询间隔。 */
    void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 配置里没填生图/生视频模型时，配置解析会退回到"对话模型"（如 qwen-plus），那不是万相模型，直接用会报错；
     * 这种情况改用默认的万相模型（与 TTS 节点遇到非 TTS 模型名时的做法一致）。
     */
    static String pickModel(String configured, String defaultModel) {
        String model = configured == null ? "" : configured.trim();
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("wan") || lower.startsWith("wanx") || lower.startsWith("qwen-image")) {
            return model;
        }
        if (!model.isEmpty()) {
            log.warn("通义生图/生视频收到非万相模型 {}，已改用 {}", model, defaultModel);
        }
        return defaultModel;
    }

    // ------------------------------------------------------------------ 生图

    /**
     * @return imageUrls（成功的图片地址）、metadata（完整任务结果）
     */
    public Map<String, Object> generateImage(ResolvedAgentPlanConfig config, String prompt, String size, int count,
                                             String negativePrompt) throws IOException {
        String model = pickModel(config.model(), DEFAULT_IMAGE_MODEL);
        if (model.startsWith("wan2.6") || model.startsWith("qwen-image")) {
            throw new IllegalArgumentException("通义生图暂只支持 image-synthesis 系列模型（如 wan2.2-t2i-flash、wan2.2-t2i-plus、"
                    + "wan2.5-t2i-preview），当前模型 " + model + " 使用另一套接口");
        }
        JSONObject request = imageRequest(model, prompt, size, count, negativePrompt);
        String taskId = taskIdOf(post(config, IMAGE_SYNTHESIS_PATH, request));

        JSONObject output = null;
        for (int i = 0; i < maxImagePolls; i++) {
            sleep(pollIntervalMs);
            JSONObject result = get(config, "/tasks/" + taskId);
            output = result.getJSONObject("output");
            String status = output == null ? "" : String.valueOf(output.getString("task_status"));
            if ("SUCCEEDED".equals(status)) {
                List<String> urls = new ArrayList<>();
                JSONArray results = output.getJSONArray("results");
                if (results != null) {
                    for (int j = 0; j < results.size(); j++) {
                        String url = results.getJSONObject(j).getString("url");
                        if (StringUtils.hasText(url)) {
                            urls.add(url);
                        }
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("imageUrls", urls);
                out.put("metadata", result);
                return out;
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                throw new IOException("通义万相生图失败: " + failureOf(output));
            }
        }
        throw new IOException("通义万相生图超时（任务 " + taskId + "）");
    }

    static JSONObject imageRequest(String model, String prompt, String size, int count, String negativePrompt) {
        JSONObject input = new JSONObject();
        input.put("prompt", prompt);
        if (StringUtils.hasText(negativePrompt)) {
            input.put("negative_prompt", negativePrompt);
        }
        JSONObject parameters = new JSONObject();
        parameters.put("size", imageSize(size));
        parameters.put("n", Math.max(1, Math.min(count, 4)));
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("input", input);
        request.put("parameters", parameters);
        return request;
    }

    /** 节点里的尺寸写法是 1024x1024；万相要 1024*1024。1K/2K 这类火山的档位写法没有对应，用默认的 1024*1024。 */
    static String imageSize(String size) {
        if (size != null) {
            var matcher = WXH.matcher(size.trim());
            if (matcher.matches()) {
                return matcher.group(1) + "*" + matcher.group(2);
            }
        }
        return "1024*1024";
    }

    // ------------------------------------------------------------------ 生视频

    @Override
    public String createVideoTask(ResolvedAgentPlanConfig config, String prompt, String referenceImageUrl,
                                  int duration, String resolution, String ratio, String cameraMotion) throws IOException {
        String model = pickModel(config.model(), DEFAULT_VIDEO_MODEL);
        JSONObject request = videoRequest(model, prompt, referenceImageUrl, duration, resolution, ratio);
        return taskIdOf(post(config, VIDEO_SYNTHESIS_PATH, request));
    }

    static JSONObject videoRequest(String model, String prompt, String referenceImageUrl, int duration,
                                   String resolution, String ratio) {
        boolean imageToVideo = StringUtils.hasText(referenceImageUrl);
        // 有参考图就用同系列的图生视频模型（wan2.2-t2v-plus → wan2.2-i2v-plus）
        String effectiveModel = imageToVideo ? model.replace("-t2v", "-i2v") : model;

        JSONObject input = new JSONObject();
        input.put("prompt", prompt);
        if (imageToVideo) {
            input.put("img_url", referenceImageUrl);
        }
        JSONObject parameters = new JSONObject();
        if (!imageToVideo) {
            parameters.put("size", videoSize(resolution, ratio));
        } else if (StringUtils.hasText(resolution)) {
            parameters.put("resolution", resolution.trim().toUpperCase(Locale.ROOT));
        }
        if (supportsConfigurableDuration(effectiveModel)) {
            parameters.put("duration", duration);
        }
        JSONObject request = new JSONObject();
        request.put("model", effectiveModel);
        request.put("input", input);
        request.put("parameters", parameters);
        return request;
    }

    /** wan2.5 可选 5 / 10 秒，wan2.6 可选 2~15 秒；wan2.2 及更早的模型固定 5 秒，传了 duration 反而可能被拒。 */
    static boolean supportsConfigurableDuration(String model) {
        String lower = model.toLowerCase(Locale.ROOT);
        return lower.startsWith("wan2.5") || lower.startsWith("wan2.6");
    }

    /** 分辨率档位 × 画幅 → 官方文档列出的合法 size。默认 480p、16:9（最省钱）。 */
    static String videoSize(String resolution, String ratio) {
        String tier = resolution == null ? "480p" : resolution.trim().toLowerCase(Locale.ROOT);
        if (WXH.matcher(tier).matches()) {
            return WXH.matcher(tier).replaceFirst("$1*$2");
        }
        String shape = ratio == null ? "16:9" : ratio.trim();
        boolean portrait = shape.equals("9:16") || shape.equals("3:4");
        boolean square = shape.equals("1:1");
        return switch (tier) {
            case "720p" -> square ? "960*960" : portrait ? "720*1280" : "1280*720";
            case "1080p" -> square ? "1440*1440" : portrait ? "1080*1920" : "1920*1080";
            default -> square ? "624*624" : portrait ? "480*832" : "832*480";
        };
    }

    @Override
    public Map<String, Object> getVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        JSONObject response = get(config, "/tasks/" + taskId);
        JSONObject output = response.getJSONObject("output");
        String status = output == null ? "" : String.valueOf(output.getString("task_status"));

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("status", normalizeStatus(status));
        task.put("videoUrl", output == null ? null : output.getString("video_url"));
        task.put("coverUrl", null);
        task.put("raw", response);
        if ("FAILED".equals(status) && output != null) {
            task.put("message", failureOf(output));
        }
        return task;
    }

    /** 统一成视频节点认得的状态词：运行中 / 成功 / 失败 / 已取消 / 已过期。 */
    static String normalizeStatus(String status) {
        return switch (status == null ? "" : status) {
            case "SUCCEEDED" -> "succeeded";
            case "FAILED" -> "failed";
            case "CANCELED" -> "canceled";
            case "UNKNOWN" -> "expired"; // 任务不存在或已超过 24 小时有效期
            default -> "running";        // PENDING / RUNNING
        };
    }

    @Override
    public void cancelVideoTask(ResolvedAgentPlanConfig config, String taskId) throws IOException {
        post(config, "/tasks/" + taskId + "/cancel", null);
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * 配置里的"API 地址"可能是 https://dashscope.aliyuncs.com/api/v1、compatible-mode 地址、wss 地址（配音用的），
     * 统一整理成 HTTP 的 /api/v1 基地址。
     */
    static String httpBase(String apiUrl) {
        String url = StringUtils.hasText(apiUrl) ? apiUrl.trim() : "https://dashscope.aliyuncs.com";
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("wss://")) {
            url = "https://" + url.substring("wss://".length());
        } else if (url.startsWith("ws://")) {
            url = "http://" + url.substring("ws://".length());
        }
        url = url.replace("/api-ws/v1/inference", "")
                .replace("/compatible-mode/v1", "")
                .replace("/compatible-mode", "")
                .replace("/api/v1", "");
        return url + "/api/v1";
    }

    private JSONObject post(ResolvedAgentPlanConfig config, String path, JSONObject body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(httpBase(config.apiUrl()) + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json");
        if (!path.endsWith("/cancel")) {
            builder.header("X-DashScope-Async", "enable");
        }
        builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
        return send(builder.build());
    }

    private JSONObject get(ResolvedAgentPlanConfig config, String path) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(httpBase(config.apiUrl()) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.apiKey())
                .GET().build());
    }

    private JSONObject send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 != 2) {
                // 错误响应里只有 request_id / code / message，不含密钥
                throw new IOException("通义接口返回 HTTP " + response.statusCode() + ": " + body);
            }
            JSONObject json = body.isBlank() ? new JSONObject() : JSON.parseObject(body);
            return json == null ? new JSONObject() : json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    private static String taskIdOf(JSONObject response) throws IOException {
        JSONObject output = response.getJSONObject("output");
        String taskId = output == null ? null : output.getString("task_id");
        if (!StringUtils.hasText(taskId)) {
            throw new IOException("通义接口未返回 task_id: " + response);
        }
        return taskId;
    }

    private static String failureOf(JSONObject output) {
        String code = output.getString("code");
        String message = output.getString("message");
        return (code == null ? "" : code + " ") + (message == null ? output.getString("task_status") : message);
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待任务时被中断", e);
        }
    }
}
