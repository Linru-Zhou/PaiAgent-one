package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.service.OutputFolder;
import com.paiagent.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流管理控制器
 */
@Tag(name = "工作流管理接口")
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    
    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private OutputFolder outputFolder;
    
    @Operation(summary = "创建工作流")
    @PostMapping
    public Result<WorkflowResponse> createWorkflow(@Valid @RequestBody WorkflowRequest request) {
        WorkflowResponse response = workflowService.createWorkflow(request);
        return Result.success(response);
    }
    
    @Operation(summary = "更新工作流")
    @PutMapping("/{id}")
    public Result<WorkflowResponse> updateWorkflow(@PathVariable Long id, @Valid @RequestBody WorkflowRequest request) {
        WorkflowResponse response = workflowService.updateWorkflow(id, request);
        return Result.success(response);
    }
    
    @Operation(summary = "删除工作流")
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkflow(@PathVariable Long id) {
        workflowService.deleteWorkflow(id);
        return Result.success();
    }
    
    @Operation(summary = "获取工作流详情")
    @GetMapping("/{id}")
    public Result<WorkflowResponse> getWorkflow(@PathVariable Long id) {
        WorkflowResponse response = workflowService.getWorkflowById(id);
        return Result.success(response);
    }
    
    @Operation(summary = "查询工作流列表")
    @GetMapping
    public Result<List<WorkflowResponse>> listWorkflows() {
        List<WorkflowResponse> list = workflowService.listWorkflows();
        return Result.success(list);
    }

    /**
     * 保存前端画布导出的流程图：PNG 由浏览器渲染后以 Base64 传来，连同（密钥已脱敏的）流程 JSON 一起
     * 存到可见的输出文件夹 workflows/ 下，文件名带工作流编号和名称。
     */
    @Operation(summary = "保存流程图图片与流程 JSON 到输出文件夹")
    @PostMapping("/{id}/export-image")
    public Result<Map<String, String>> exportImage(@PathVariable Long id, @RequestBody Map<String, String> body) {
        if (!outputFolder.enabled()) {
            return Result.error("输出文件夹未启用（paiagent.output.dir 为空）");
        }
        WorkflowResponse workflow = workflowService.getWorkflowById(id);
        String png = body.get("png");
        if (png == null || png.isBlank()) {
            return Result.error("缺少 png 内容");
        }
        int comma = png.indexOf(',');
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(comma >= 0 ? png.substring(comma + 1) : png);
        } catch (IllegalArgumentException e) {
            return Result.error("png 不是合法的 Base64");
        }
        String base = "workflows/" + id + "-" + workflow.getName().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        Path image = outputFolder.saveBytes(base + ".png", bytes);
        Path json = outputFolder.saveBytes(base + ".json", workflow.getFlowData().getBytes(StandardCharsets.UTF_8));
        if (image == null) {
            return Result.error("保存失败");
        }
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("image", image.toString());
        paths.put("flowJson", json == null ? null : json.toString());
        return Result.success(paths);
    }
}
