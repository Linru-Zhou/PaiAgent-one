package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.StepFunImageClient;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerateNodeExecutorTest {

    @Mock
    private AgentPlanConfigResolver configResolver;

    @Mock
    private VolcengineAgentPlanClient agentPlanClient;

    @Mock
    private StepFunImageClient stepFunImageClient;

    @Mock
    private MinioService minioService;

    private ImageGenerateNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ImageGenerateNodeExecutor(configResolver, agentPlanClient, stepFunImageClient, minioService);
    }

    @Test
    void execute_StepProvider_UsesStepFunImageClient() throws Exception {
        WorkflowNode node = new WorkflowNode();
        Map<String, Object> data = new HashMap<>();
        data.put("size", "1024x1024");
        node.setData(data);

        ResolvedAgentPlanConfig config = mock(ResolvedAgentPlanConfig.class);
        when(config.provider()).thenReturn("step");
        when(config.model()).thenReturn("step-1x-medium");
        when(configResolver.resolveImageConfig(node)).thenReturn(config);
        doNothing().when(configResolver).validateApiConfig(config, "图片生成");
        when(stepFunImageClient.generateImage(config, "画一只猫", null, "1024x1024", 1, null, null, 0, 0, 0, true))
                .thenReturn(Map.of("imageUrls", List.of("https://step.example/image.png")));
        when(minioService.uploadFromUrl(eq("https://step.example/image.png"), anyString(), eq("image/png")))
                .thenReturn("https://minio.example/image.png");

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "画一只猫"));

        assertEquals("step-1x-medium", result.get("model"));
        assertEquals("https://minio.example/image.png", result.get("imageUrl"));
        verify(stepFunImageClient).generateImage(config, "画一只猫", null, "1024x1024", 1, null, null, 0, 0, 0, true);
        verify(agentPlanClient, never()).generateImage(eq(config), anyString(), eq(null), anyString(), eq(1), eq(null), eq(null));
    }

    @Test
    void execute_OverlayText_DrawsTheTitleWithRealFontsInsteadOfTrustingTheModel() throws Exception {
        // 模型生成的是一张纯深蓝的图（没有任何文字），标题必须由代码画上去
        java.awt.image.BufferedImage blank = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = blank.createGraphics();
        g.setColor(new java.awt.Color(30, 40, 70));
        g.fillRect(0, 0, 512, 512);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(blank, "png", png);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/img.png", exchange -> {
            exchange.sendResponseHeaders(200, png.size());
            exchange.getResponseBody().write(png.toByteArray());
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/img.png";
            WorkflowNode node = new WorkflowNode();
            Map<String, Object> data = new HashMap<>();
            data.put("size", "1024x1024");
            data.put("inputParams", List.of(
                    Map.of("name", "prompt", "type", "reference", "referenceNode", "img_prompt.output"),
                    Map.of("name", "overlayText", "type", "reference", "referenceNode", "title.output")));
            node.setData(data);

            ResolvedAgentPlanConfig config = mock(ResolvedAgentPlanConfig.class);
            when(config.provider()).thenReturn("step");
            when(config.model()).thenReturn("step-1x-medium");
            when(configResolver.resolveImageConfig(node)).thenReturn(config);
            doNothing().when(configResolver).validateApiConfig(config, "图片生成");
            when(stepFunImageClient.generateImage(eq(config), eq("深蓝底色的咖啡杯，顶部留白"), eq(null), eq("1024x1024"), eq(1),
                    eq(null), eq(null), eq(0), eq(0.0), eq(0), eq(true)))
                    .thenReturn(Map.of("imageUrls", List.of(url)));
            org.mockito.ArgumentCaptor<byte[]> composed = org.mockito.ArgumentCaptor.forClass(byte[].class);
            when(minioService.uploadFromBytes(composed.capture(), anyString(), eq("image/png")))
                    .thenReturn("https://minio.example/composed.png");

            // 两个上游节点的输出都叫 output：必须按节点 id 精确取，不能取到被后到的那个盖掉的值
            Map<String, Object> input = new HashMap<>();
            input.put("output", "标题节点的输出（合并后后到的会盖掉先到的）");
            input.put("__nodeOutputs__", Map.of(
                    "img_prompt", Map.of("output", "深蓝底色的咖啡杯，顶部留白"),
                    "title", Map.of("output", "并发不安全\nConcurrentHashMap")));

            Map<String, Object> result = executor.execute(node, input);

            assertEquals("https://minio.example/composed.png", result.get("imageUrl"));
            assertEquals("并发不安全\nConcurrentHashMap", result.get("overlayText"));
            verify(minioService, never()).uploadFromUrl(anyString(), anyString(), anyString());
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(composed.getValue()));
            int bright = 0;
            for (int y = 0; y < 170; y++) {
                for (int x = 0; x < 512; x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 255) > 220 && ((rgb >> 8) & 255) > 220 && (rgb & 255) > 220) {
                        bright++;
                    }
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(bright > 1000, "标题应该被画在图的顶部，白色像素数 " + bright);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void execute_OverlayText_ThatCannotBeDrawnFailsBeforeCallingThePaidImageApi() {
        WorkflowNode node = new WorkflowNode();
        Map<String, Object> data = new HashMap<>();
        data.put("overlayText", new String(Character.toChars(0x10FFFF)));
        node.setData(data);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "画一只猫")));

        org.mockito.Mockito.verifyNoInteractions(configResolver, stepFunImageClient, agentPlanClient, minioService);
    }
}
