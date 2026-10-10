package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.DashScopeMediaClient;
import com.paiagent.service.ImageTextOverlay;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.StepFunImageClient;
import com.paiagent.service.VolcengineAgentPlanClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ImageGenerateNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final StepFunImageClient stepFunImageClient;
    private final MinioService minioService;
    private final ImageTextOverlay textOverlay = new ImageTextOverlay();

    /** 通义万相（百炼）的客户端；测试里手动构造执行器时没有。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DashScopeMediaClient dashScopeClient;

    public ImageGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                                     VolcengineAgentPlanClient agentPlanClient,
                                     StepFunImageClient stepFunImageClient,
                                     MinioService minioService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.stepFunImageClient = stepFunImageClient;
        this.minioService = minioService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        String prompt = configuredTextInput(node, input, "prompt");
        if (prompt == null) {
            prompt = textValue(node, input, "prompt", "input");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("图片生成节点缺少 prompt");
        }

        // 要在图上叠加的文字（输入配置里名为 overlayText 的参数，可以是固定文字或引用上游节点）。
        // 付费生图之前先确认能画出来：否则图片生成完才发现没有字体，钱就白花了
        String overlayText = configuredTextInput(node, input, "overlayText");
        if (overlayText == null) {
            overlayText = stringData(node, "overlayText", null);
        }
        textOverlay.assertCanDraw(overlayText);

        ResolvedAgentPlanConfig config = configResolver.resolveImageConfig(node);
        configResolver.validateApiConfig(config, "图片生成");

        Map<String, Object> result = generateImage(
                config,
                prompt,
                stringData(node, "referenceImageUrl", null),
                stringData(node, "size", "2K"),
                intData(node, "count", 1),
                stringData(node, "negativePrompt", null),
                stringData(node, "style", null),
                intData(node, "steps", 0),
                doubleData(node, "cfgScale", 0),
                intData(node, "seed", 0),
                booleanData(node, "textMode", true)
        );

        List<String> sourceUrls = (List<String>) result.getOrDefault("imageUrls", List.of());
        List<String> persistedUrls = new ArrayList<>();
        boolean overlay = StringUtils.hasText(overlayText) && !ImageTextOverlay.lines(overlayText).isEmpty();
        for (String sourceUrl : sourceUrls) {
            String objectName = "images/generated-" + System.currentTimeMillis() + ".png";
            if (overlay) {
                // 模型画的字不可靠，这里由代码用真字体把标题写上去；失败就让节点失败，不能交付一张缺标题的图
                byte[] composed = textOverlay.drawOnUrl(sourceUrl, overlayText, stringData(node, "overlayPosition", "top"));
                persistedUrls.add(minioService.uploadFromBytes(composed, objectName, "image/png"));
            } else {
                persistedUrls.add(persistMedia(sourceUrl, objectName, "image/png"));
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        if (overlay) {
            output.put("overlayText", overlayText);
        }
        output.put("imageUrl", persistedUrls.isEmpty() ? null : persistedUrls.get(0));
        output.put("imageUrls", persistedUrls);
        output.put("prompt", prompt);
        output.put("model", config.model());
        output.put("metadata", result.get("metadata"));
        output.put("output", persistedUrls.isEmpty() ? null : persistedUrls.get(0));
        applyOutputParams(node, output);
        return output;
    }

    private Map<String, Object> generateImage(ResolvedAgentPlanConfig config, String prompt,
                                              String referenceImageUrl, String size, int count,
                                              String negativePrompt, String style,
                                              int steps, double cfgScale, int seed, boolean textMode) throws Exception {
        return switch (config.provider()) {
            case "volcengine_agent_plan" -> agentPlanClient.generateImage(
                    config,
                    prompt,
                    referenceImageUrl,
                    size,
                    count,
                    negativePrompt,
                    style
            );
            case "step" -> stepFunImageClient.generateImage(
                    config,
                    prompt,
                    referenceImageUrl,
                    size,
                    count,
                    negativePrompt,
                    style,
                    steps,
                    cfgScale,
                    seed,
                    textMode
            );
            case "qwen" -> {
                if (dashScopeClient == null) {
                    throw new IllegalStateException("通义生图客户端未初始化");
                }
                yield dashScopeClient.generateImage(config, prompt, size, count, negativePrompt);
            }
            default -> throw new IllegalArgumentException("图片生成暂不支持提供商: " + config.provider());
        };
    }

    private boolean booleanData(WorkflowNode node, String field, boolean defaultValue) {
        Object value = node.getData().get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = asText(value);
        return StringUtils.hasText(text) ? Boolean.parseBoolean(text) : defaultValue;
    }

    private String persistMedia(String sourceUrl, String objectName, String contentType) {
        if (!StringUtils.hasText(sourceUrl)) {
            return sourceUrl;
        }
        try {
            return minioService.uploadFromUrl(sourceUrl, objectName, contentType);
        } catch (Exception e) {
            log.warn("图片转存 MinIO 失败，保留原始 URL: {}", e.getMessage());
            return sourceUrl;
        }
    }

    @Override
    public String getSupportedNodeType() {
        return "image_generate";
    }
}
