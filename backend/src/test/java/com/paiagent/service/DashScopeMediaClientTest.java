package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通义万相（百炼）生图 / 生视频客户端。服务端是用 JDK HttpServer 写的假百炼，
 * 请求与响应的格式按官方文档（以及用真实密钥调用生图接口时看到的实际响应）编写。
 */
class DashScopeMediaClientTest {

    private record Call(String method, String path, String authorization, String asyncHeader, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger imagePolls = new AtomicInteger();
    private final AtomicInteger videoPolls = new AtomicInteger();
    private DashScopeMediaClient client;
    private ResolvedAgentPlanConfig config;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath().substring("/api/v1".length());
            calls.add(new Call(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-DashScope-Async"), body));
            int status = 200;
            String response = switch (exchange.getRequestMethod() + " " + path) {
                case "POST /services/aigc/text2image/image-synthesis" -> "{\"output\":{\"task_id\":\"img-1\",\"task_status\":\"PENDING\"}}";
                case "GET /tasks/img-1" -> imagePolls.incrementAndGet() < 2
                        ? "{\"output\":{\"task_id\":\"img-1\",\"task_status\":\"RUNNING\"}}"
                        : "{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"orig_prompt\":\"p\",\"url\":\"http://cdn/a.png\"},{\"code\":\"X\",\"message\":\"审核未通过\"},{\"url\":\"http://cdn/b.png\"}]},\"usage\":{\"image_count\":2}}";
                case "POST /services/aigc/video-generation/video-synthesis" -> "{\"output\":{\"task_id\":\"vid-1\",\"task_status\":\"PENDING\"}}";
                case "GET /tasks/vid-1" -> videoPolls.incrementAndGet() < 2
                        ? "{\"output\":{\"task_status\":\"RUNNING\"}}"
                        : "{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"http://cdn/v.mp4\"}}";
                case "GET /tasks/vid-fail" -> "{\"output\":{\"task_status\":\"FAILED\",\"code\":\"InvalidParameter\",\"message\":\"The size is not match\"}}";
                case "GET /tasks/vid-gone" -> "{\"output\":{\"task_status\":\"UNKNOWN\"}}";
                case "POST /tasks/vid-1/cancel" -> "{\"request_id\":\"r\"}";
                case "POST /tasks/vid-run/cancel" -> {
                    status = 400;
                    yield "{\"code\":\"UnsupportedOperation\",\"message\":\"Failed to cancel the task, please confirm if the task is in PENDING status.\"}";
                }
                default -> {
                    status = 404;
                    yield "{\"code\":\"NotFound\"}";
                }
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new DashScopeMediaClient();
        client.setPollIntervalMs(5);
        config = new ResolvedAgentPlanConfig(null, "qwen", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "test-key", "wan2.2-t2i-flash", null, null, null, false);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void imageGenerationSubmitsAnAsyncTaskPollsItAndReturnsOnlySuccessfulUrls() throws Exception {
        Map<String, Object> result = client.generateImage(config, "封面图", "1024x1024", 2, "模糊");

        assertEquals(List.of("http://cdn/a.png", "http://cdn/b.png"), result.get("imageUrls"), "被审核拒绝的那张没有 url，要跳过");
        Call create = calls.get(0);
        assertEquals("POST", create.method());
        assertEquals("Bearer test-key", create.authorization());
        assertEquals("enable", create.asyncHeader(), "万相只支持异步调用，缺了这个头会被拒绝");
        JSONObject body = JSON.parseObject(create.body());
        assertEquals("wan2.2-t2i-flash", body.getString("model"));
        assertEquals("封面图", body.getJSONObject("input").getString("prompt"));
        assertEquals("模糊", body.getJSONObject("input").getString("negative_prompt"));
        assertEquals("1024*1024", body.getJSONObject("parameters").getString("size"), "1024x1024 要转成万相的 1024*1024");
        assertEquals(2, body.getJSONObject("parameters").getIntValue("n"));
        assertNull(calls.get(1).asyncHeader(), "轮询不需要异步头");
        assertEquals("/tasks/img-1", calls.get(1).path());
    }

    @Test
    void imageGenerationRejectsModelsThatUseADifferentEndpoint() {
        ResolvedAgentPlanConfig wan26 = new ResolvedAgentPlanConfig(null, "qwen", config.apiUrl(), "k", "wan2.6-t2i", null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> client.generateImage(wan26, "p", "1024x1024", 1, null));
        assertTrue(calls.isEmpty(), "不支持的模型应在发请求之前就拒绝");
    }

    @Test
    void videoTaskLifecycleMapsStatusesTheNodeUnderstands() throws Exception {
        ResolvedAgentPlanConfig video = new ResolvedAgentPlanConfig(null, "qwen", config.apiUrl(), "test-key", "wan2.5-t2v-preview", null, null, null, false);
        String taskId = client.createVideoTask(video, "镜头缓慢推进", null, 10, "720p", "9:16", "ignored");
        assertEquals("vid-1", taskId);

        JSONObject body = JSON.parseObject(calls.get(0).body());
        assertEquals("wan2.5-t2v-preview", body.getString("model"));
        assertEquals("720*1280", body.getJSONObject("parameters").getString("size"), "720p + 9:16");
        assertEquals(10, body.getJSONObject("parameters").getIntValue("duration"), "wan2.5 支持 5/10 秒");
        assertEquals("enable", calls.get(0).asyncHeader());

        assertEquals("running", client.getVideoTask(video, taskId).get("status"));
        Map<String, Object> done = client.getVideoTask(video, taskId);
        assertEquals("succeeded", done.get("status"));
        assertEquals("http://cdn/v.mp4", done.get("videoUrl"));

        Map<String, Object> failed = client.getVideoTask(video, "vid-fail");
        assertEquals("failed", failed.get("status"));
        assertTrue(String.valueOf(failed.get("message")).contains("The size is not match"));
        assertEquals("expired", client.getVideoTask(video, "vid-gone").get("status"), "UNKNOWN = 任务不存在/超过 24 小时");
    }

    @Test
    void cancelWorksForQueuedTasksAndSurfacesTheProviderRefusalForRunningOnes() throws Exception {
        client.cancelVideoTask(config, "vid-1");
        Call cancel = calls.get(0);
        assertEquals("/tasks/vid-1/cancel", cancel.path());
        assertNull(cancel.asyncHeader());

        IOException refused = assertThrows(IOException.class, () -> client.cancelVideoTask(config, "vid-run"));
        assertTrue(refused.getMessage().contains("UnsupportedOperation"), refused.getMessage());
        assertFalse(refused.getMessage().contains("test-key"), "错误信息里不能出现密钥");
    }

    @Test
    void imageToVideoUsesTheSeriesImageModelAndFixedDurationModelsOmitDuration() {
        JSONObject i2v = DashScopeMediaClient.videoRequest("wan2.2-t2v-plus", "p", "http://img/x.png", 5, "480p", "16:9");
        assertEquals("wan2.2-i2v-plus", i2v.getString("model"));
        assertEquals("http://img/x.png", i2v.getJSONObject("input").getString("img_url"));

        JSONObject t2v = DashScopeMediaClient.videoRequest("wan2.2-t2v-plus", "p", null, 5, null, null);
        assertEquals("832*480", t2v.getJSONObject("parameters").getString("size"), "默认 480p 16:9，最省钱");
        assertFalse(t2v.getJSONObject("parameters").containsKey("duration"), "wan2.2 固定 5 秒，传 duration 可能被拒");
    }

    @Test
    void sizeAndUrlNormalisation() {
        assertEquals("1024*1024", DashScopeMediaClient.imageSize("2K"), "火山的 1K/2K 档位写法在万相里没有对应，用默认");
        assertEquals("1280*720", DashScopeMediaClient.imageSize("1280x720"));
        assertEquals("1920*1080", DashScopeMediaClient.videoSize("1080p", "adaptive"));
        assertEquals("624*624", DashScopeMediaClient.videoSize("480p", "1:1"));
        assertEquals("960*540", DashScopeMediaClient.videoSize("960x540", null));

        assertEquals("https://dashscope.aliyuncs.com/api/v1", DashScopeMediaClient.httpBase(null));
        assertEquals("https://dashscope.aliyuncs.com/api/v1", DashScopeMediaClient.httpBase("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1", DashScopeMediaClient.httpBase("wss://dashscope.aliyuncs.com/api-ws/v1/inference"));
        assertEquals("https://dashscope-intl.aliyuncs.com/api/v1", DashScopeMediaClient.httpBase("https://dashscope-intl.aliyuncs.com/api/v1/"));

        assertEquals("wan2.2-t2i-flash", DashScopeMediaClient.pickModel("qwen-plus", "wan2.2-t2i-flash"), "退回到对话模型名时改用默认万相模型");
        assertEquals("wan2.5-t2v-preview", DashScopeMediaClient.pickModel("wan2.5-t2v-preview", "x"));
    }
}
