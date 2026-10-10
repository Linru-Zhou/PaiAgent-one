package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 输出节点的模板引用：被条件分支跳过的节点不能用别的节点的同名输出顶替。 */
class OutputNodeExecutorTest {

    private WorkflowNode node() {
        WorkflowNode node = new WorkflowNode();
        node.setId("out");
        node.setType("output");
        Map<String, Object> data = new HashMap<>();
        data.put("responseContent", "视频:{{video}}|分镜:{{storyboard}}");
        data.put("outputParams", List.of(
                Map.of("name", "video", "type", "reference", "referenceNode", "clip.videoUrl"),
                Map.of("name", "storyboard", "type", "reference", "referenceNode", "storyboard.output")));
        node.setData(data);
        return node;
    }

    @Test
    void aSkippedNodeYieldsAnEmptyValueInsteadOfAnotherNodesOutput() {
        Map<String, Object> input = new HashMap<>();
        input.put("output", "http://cdn/v.mp4"); // 合并后的上游数据里，"output" 是 clip 的输出
        input.put("videoUrl", "http://cdn/v.mp4");
        input.put("__nodeOutputs__", Map.of("clip", Map.of("videoUrl", "http://cdn/v.mp4", "output", "http://cdn/v.mp4")));

        Map<String, Object> result = new OutputNodeExecutor().execute(node(), input);

        assertEquals("视频:http://cdn/v.mp4|分镜:", result.get("output"), "storyboard 被跳过，应该是空，不能是视频地址");
    }

    @Test
    void withoutNodeOutputsItStillFallsBackToTheMergedInputByName() {
        Map<String, Object> input = new HashMap<>();
        input.put("videoUrl", "http://cdn/v.mp4");
        input.put("output", "分镜文本");

        Map<String, Object> result = new OutputNodeExecutor().execute(node(), input);

        assertEquals("视频:http://cdn/v.mp4|分镜:分镜文本", result.get("output"), "没有 __nodeOutputs__ 时保持原来的按名字回退");
    }
}
