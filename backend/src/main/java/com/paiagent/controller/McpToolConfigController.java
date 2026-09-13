package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.AgentPlanWebSearchMcpRequest;
import com.paiagent.dto.McpToolConfigRequest;
import com.paiagent.dto.McpToolTestRequest;
import com.paiagent.service.McpToolConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "MCP 工具管理接口")
@RestController
@RequestMapping("/api/mcp-tools")
public class McpToolConfigController {

    private final McpToolConfigService mcpToolConfigService;

    public McpToolConfigController(McpToolConfigService mcpToolConfigService) {
        this.mcpToolConfigService = mcpToolConfigService;
    }

    @Operation(summary = "查询 MCP 工具列表")
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.success(mcpToolConfigService.listConfigs());
    }

    @Operation(summary = "新增 MCP 工具")
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody McpToolConfigRequest request) {
        try {
            return Result.success(mcpToolConfigService.createConfig(request));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "新增 Agent Plan 联网搜索 MCP 工具")
    @PostMapping("/agent-plan-web-search")
    public Result<Map<String, Object>> createAgentPlanWebSearch(@Valid @RequestBody AgentPlanWebSearchMcpRequest request) {
        try {
            return Result.success(mcpToolConfigService.createAgentPlanWebSearch(request));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "更新 MCP 工具")
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @Valid @RequestBody McpToolConfigRequest request) {
        try {
            return Result.success(mcpToolConfigService.updateConfig(id, request));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "删除 MCP 工具")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            mcpToolConfigService.deleteConfig(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "测试 MCP 工具")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id,
                                            @RequestBody(required = false) McpToolTestRequest request) {
        try {
            return Result.success(mcpToolConfigService.testConfig(id, request == null ? null : request.getQuery()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("MCP 工具调用失败: " + e.getMessage());
        }
    }
}
