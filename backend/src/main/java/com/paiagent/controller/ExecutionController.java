package com.paiagent.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.Result;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionRequest;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ExecutionSnapshotResponse;
import com.paiagent.dto.ExecutionVariableResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.EngineSelector;
import com.paiagent.engine.ExecutionConflictException;
import com.paiagent.engine.ExecutionDispatcher;
import com.paiagent.engine.ExecutionRejectedException;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.WorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.entity.ExecutionVariable;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.ExecutionVariableMapper;
import com.paiagent.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Tag(name = "工作流执行接口")
@RestController
@RequestMapping("/api/workflows")
public class ExecutionController {
    
    @Autowired
    private WorkflowService workflowService;
    
    @Autowired
    private EngineSelector engineSelector;

    @Autowired
    private ExecutionDispatcher dispatcher;

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Autowired
    private ExecutionVariableMapper executionVariableMapper;
    
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
    @Operation(summary = "执行工作流")
    @PostMapping("/{id}/execute")
    public Result<ExecutionResponse> executeWorkflow(@PathVariable Long id,
                                                     @Valid @RequestBody ExecutionRequest request,
                                                     @RequestParam(required = false) String engine) {
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.error("工作流不存在");
        }

        try {
            // 使用引擎选择器选择合适的执行引擎；执行统一经过有界线程池，超载时快速拒绝而不是无限堆积
            WorkflowExecutor executor = engineSelector.selectEngine(workflow, engine);
            Future<ExecutionResponse> future = dispatcher.submit(() -> executor.execute(workflow, request.getInputData()));
            return Result.success(future.get());
        } catch (ExecutionRejectedException e) {
            return Result.error(429, e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.error("工作流执行失败: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("工作流执行被中断");
        } catch (Exception e) {
            return Result.error("工作流执行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "异步执行工作流：立即返回 executionId，进度通过 GET /executions/{executionId} 轮询")
    @PostMapping("/{id}/execute/async")
    public Result<Map<String, Object>> executeWorkflowAsync(@PathVariable Long id,
                                                            @Valid @RequestBody ExecutionRequest request,
                                                            @RequestParam(required = false) String engine) {
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.error("工作流不存在");
        }

        try {
            WorkflowExecutor executor = engineSelector.selectEngine(workflow, engine);
            AtomicReference<Long> executionId = new AtomicReference<>();
            AtomicReference<Throwable> startFailure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);

            Consumer<ExecutionEvent> callback = event -> {
                if ("WORKFLOW_START".equals(event.getEventType()) && event.getData() instanceof Number number) {
                    executionId.set(number.longValue());
                    started.countDown();
                }
            };
            dispatcher.execute(() -> {
                try {
                    executor.executeWithCallback(workflow, request.getInputData(), callback);
                } catch (Throwable t) {
                    startFailure.set(t);
                    log.error("异步执行工作流失败", t);
                } finally {
                    started.countDown();
                }
            });

            if (!started.await(10, TimeUnit.SECONDS)) {
                return Result.error("工作流启动超时，请稍后查询执行记录");
            }
            if (executionId.get() == null) {
                Throwable failure = startFailure.get();
                return Result.error("工作流启动失败: " + (failure != null ? failure.getMessage() : "未知原因"));
            }
            return Result.success(Map.of("executionId", executionId.get(), "status", "RUNNING"));
        } catch (ExecutionRejectedException e) {
            return Result.error(429, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("工作流启动被中断");
        } catch (Exception e) {
            return Result.error("工作流启动失败: " + e.getMessage());
        }
    }

    @Operation(summary = "查询某次执行的状态与各节点进度（执行中也可查询，用于断线重连/轮询）")
    @GetMapping("/{id}/executions/{executionId}")
    public Result<ExecutionResponse> getExecution(@PathVariable Long id, @PathVariable Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !id.equals(record.getFlowId())) {
            return Result.error("执行记录不存在");
        }

        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(record.getStatus());
        response.setInputData(record.getInputData());
        response.setOutputData(record.getOutputData());
        response.setDuration(record.getDuration());
        response.setErrorMessage(record.getErrorMessage());
        if ("RUNNING".equals(record.getStatus())) {
            // 执行中时 node_results 还没写入，实时进度来自逐节点落库的快照
            List<ExecutionResponse.NodeResult> live = new ArrayList<>();
            for (ExecutionSnapshot snapshot : executionSnapshotMapper.selectByExecutionId(executionId)) {
                ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
                nodeResult.setNodeId(snapshot.getNodeId());
                nodeResult.setNodeName(snapshot.getNodeType());
                nodeResult.setStatus(snapshot.getStatus());
                nodeResult.setInput(snapshot.getInputData());
                nodeResult.setOutput(snapshot.getOutputData());
                nodeResult.setDuration(snapshot.getDuration() != null ? snapshot.getDuration() : 0);
                nodeResult.setError(snapshot.getErrorMessage());
                live.add(nodeResult);
            }
            response.setNodeResults(live);
        } else {
            response.setNodeResults(parseNodeResults(record.getNodeResults()));
        }
        return Result.success(response);
    }

    @Operation(summary = "获取工作流最近一次执行记录")
    @GetMapping("/{id}/executions/latest")
    public Result<ExecutionResponse> getLatestExecution(@PathVariable Long id) {
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.error("工作流不存在");
        }

        LambdaQueryWrapper<ExecutionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionRecord::getFlowId, id)
               .orderByDesc(ExecutionRecord::getId)
               .last("LIMIT 1");

        ExecutionRecord record = executionRecordMapper.selectOne(wrapper);
        if (record == null) {
            return Result.success(null);
        }

        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(record.getStatus());
        response.setInputData(record.getInputData());
        response.setOutputData(record.getOutputData());
        response.setDuration(record.getDuration());
        response.setErrorMessage(record.getErrorMessage());
        response.setNodeResults(parseNodeResults(record.getNodeResults()));
        return Result.success(response);
    }

    @Operation(summary = "获取执行节点快照")
    @GetMapping("/{id}/executions/{executionId}/snapshots")
    public Result<List<ExecutionSnapshotResponse>> getExecutionSnapshots(@PathVariable Long id,
                                                                         @PathVariable Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !id.equals(record.getFlowId())) {
            return Result.error("执行记录不存在");
        }

        List<ExecutionSnapshot> snapshots = executionSnapshotMapper.selectByExecutionId(executionId);
        List<ExecutionSnapshotResponse> responses = new ArrayList<>();
        for (ExecutionSnapshot snapshot : snapshots) {
            responses.add(toSnapshotResponse(snapshot));
        }
        return Result.success(responses);
    }

    @Operation(summary = "获取执行变量")
    @GetMapping("/{id}/executions/{executionId}/variables")
    public Result<List<ExecutionVariableResponse>> getExecutionVariables(@PathVariable Long id,
                                                                         @PathVariable Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !id.equals(record.getFlowId())) {
            return Result.error("执行记录不存在");
        }

        List<ExecutionVariable> variables = executionVariableMapper.selectByExecutionId(executionId);
        List<ExecutionVariableResponse> responses = new ArrayList<>();
        for (ExecutionVariable variable : variables) {
            responses.add(toVariableResponse(variable));
        }
        return Result.success(responses);
    }

    @Operation(summary = "从断点继续执行工作流")
    @PostMapping("/{id}/executions/{executionId}/resume")
    public Result<ExecutionResponse> resumeExecution(@PathVariable Long id,
                                                     @PathVariable Long executionId,
                                                     @RequestBody(required = false) ResumeExecutionRequest request) {
        Workflow workflow = workflowService.getById(id);
        if (workflow == null) {
            return Result.error("工作流不存在");
        }

        try {
            // 快照是引擎无关的：无论这次执行最初由哪个引擎跑的，续跑都由 DAG 调度器基于快照完成
            ExecutionResponse response = workflowEngine.resumeExecution(workflow, executionId, request, null);
            return Result.success(response);
        } catch (ExecutionConflictException e) {
            return Result.error(409, e.getMessage());
        } catch (Exception e) {
            log.error("断点续执行失败", e);
            return Result.error("断点续执行失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "实时执行工作流(SSE)")
    @GetMapping(value = "/{id}/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflowStream(@PathVariable Long id,
                                            @RequestParam String inputData,
                                            @RequestParam(required = false) String engine) {
        SseEmitter emitter = new SseEmitter(300000L);
        String emitterId = id + "_" + System.currentTimeMillis();
        emitters.put(emitterId, emitter);
        // 浏览器断开后只停止推送，不中断执行：执行结果照常落库，客户端可以按 executionId 重新查询
        AtomicBoolean clientGone = new AtomicBoolean(false);

        emitter.onCompletion(() -> emitters.remove(emitterId));
        emitter.onTimeout(() -> {
            clientGone.set(true);
            emitters.remove(emitterId);
        });
        emitter.onError((e) -> {
            clientGone.set(true);
            emitters.remove(emitterId);
        });

        Consumer<ExecutionEvent> eventCallback = event -> {
            if (clientGone.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(event));
            } catch (IOException | IllegalStateException e) {
                clientGone.set(true);
                log.warn("SSE 客户端已断开，停止推送（执行继续在后台完成）: {}", e.getMessage());
                emitters.remove(emitterId);
            }
        };

        try {
            dispatcher.execute(() -> {
                try {
                    Workflow workflow = workflowService.getById(id);
                    if (workflow == null) {
                        sendError(emitter, clientGone, "工作流不存在");
                        return;
                    }

                    // 使用引擎选择器选择合适的执行引擎
                    WorkflowExecutor executor = engineSelector.selectEngine(workflow, engine);
                    executor.executeWithCallback(workflow, inputData, eventCallback);
                } catch (Exception e) {
                    log.error("工作流执行失败", e);
                    sendError(emitter, clientGone, e.getMessage());
                } finally {
                    completeQuietly(emitter);
                }
            });
        } catch (ExecutionRejectedException e) {
            sendError(emitter, clientGone, e.getMessage());
            completeQuietly(emitter);
        }

        return emitter;
    }

    private void sendError(SseEmitter emitter, AtomicBoolean clientGone, String message) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data(ExecutionEvent.workflowComplete("FAILED", message, 0)));
        } catch (IOException | IllegalStateException ex) {
            log.warn("发送错误事件失败: {}", ex.getMessage());
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开时 complete 可能抛异常，忽略即可
        }
    }

    private List<ExecutionResponse.NodeResult> parseNodeResults(String nodeResults) {
        if (nodeResults == null || nodeResults.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return JSON.parseArray(nodeResults, ExecutionResponse.NodeResult.class);
        } catch (Exception e) {
            log.warn("解析执行节点结果失败", e);
            return Collections.emptyList();
        }
    }

    private ExecutionSnapshotResponse toSnapshotResponse(ExecutionSnapshot snapshot) {
        ExecutionSnapshotResponse response = new ExecutionSnapshotResponse();
        response.setId(snapshot.getId());
        response.setExecutionId(snapshot.getExecutionId());
        response.setFlowId(snapshot.getFlowId());
        response.setNodeId(snapshot.getNodeId());
        response.setNodeType(snapshot.getNodeType());
        response.setNodeName(snapshot.getNodeName());
        response.setStatus(snapshot.getStatus());
        response.setInputData(parseJsonMap(snapshot.getInputData()));
        response.setOutputData(parseJsonMap(snapshot.getOutputData()));
        response.setErrorMessage(snapshot.getErrorMessage());
        response.setStartedAt(snapshot.getStartedAt());
        response.setCompletedAt(snapshot.getCompletedAt());
        response.setDuration(snapshot.getDuration());
        response.setRetryCount(snapshot.getRetryCount());
        response.setExecutionOrder(snapshot.getExecutionOrder());
        response.setCreatedAt(snapshot.getCreatedAt());
        return response;
    }

    private ExecutionVariableResponse toVariableResponse(ExecutionVariable variable) {
        ExecutionVariableResponse response = new ExecutionVariableResponse();
        response.setId(variable.getId());
        response.setExecutionId(variable.getExecutionId());
        response.setVariableName(variable.getVariableName());
        response.setVariableType(variable.getVariableType());
        response.setVariableValue(variable.getVariableValue());
        response.setIsModified(variable.getIsModified() != null && variable.getIsModified() == 1);
        response.setCreatedAt(variable.getCreatedAt());
        response.setUpdatedAt(variable.getUpdatedAt());
        return response;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("解析 JSON 对象失败: {}", json, e);
            return Collections.emptyMap();
        }
    }
}
