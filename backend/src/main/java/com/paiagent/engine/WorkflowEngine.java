package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.entity.ExecutionVariable;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.ExecutionVariableMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkflowEngine implements WorkflowExecutor {

    /** 只在运行时传递、不应该写进快照/执行记录/输出的字段 */
    private static final Set<String> RUNTIME_ONLY_KEYS = Set.of("__nodeOutputs__", "__executionId__", "__idempotencyKey__");
    
    @Autowired
    private DAGParser dagParser;

    @Autowired
    private WorkflowConfigParser workflowConfigParser;
    
    @Autowired
    private NodeExecutorFactory executorFactory;
    
    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired(required = false)
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Autowired(required = false)
    private ExecutionVariableMapper executionVariableMapper;

    /** 节点并发线程池；为 null（比如单测里手工构造）时退化为串行执行 */
    @Autowired(required = false)
    @Qualifier("nodePool")
    private ThreadPoolExecutor nodePool;

    /** 是否并发执行同一层互不依赖的节点（运维开关，出问题时可一键退回串行） */
    @Value("${paiagent.engine.parallel-enabled:true}")
    private boolean parallelEnabled = true;
    
    @Override
    public ExecutionResponse execute(Workflow workflow, String inputData) {
        return executeWithCallback(workflow, inputData, null);
    }
    
    @Override
    public ExecutionResponse executeWithCallback(Workflow workflow, String inputData, Consumer<ExecutionEvent> eventCallback) {
        long startTime = System.currentTimeMillis();
        
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        List<WorkflowNode> sortedNodes = dagParser.parse(config);
        List<WorkflowEdge> edges = config.getEdges() != null ? config.getEdges() : new ArrayList<>();
        EdgeIndexes indexes = buildEdgeIndexes(edges);
        ExecutionRecord record = createRunningRecord(workflow.getId(), inputData);

        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
        }

        return executeNodes(record, workflow.getId(), inputData, sortedNodes, indexes, new ResumeState(), startTime, eventCallback);
    }

    public ExecutionResponse resumeExecution(Workflow workflow,
                                             Long executionId,
                                             ResumeExecutionRequest request,
                                             Consumer<ExecutionEvent> eventCallback) {
        if (executionSnapshotMapper == null) {
            throw new IllegalStateException("断点续执行未启用: executionSnapshotMapper 不可用");
        }

        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !Objects.equals(record.getFlowId(), workflow.getId())) {
            throw new IllegalArgumentException("执行记录不存在或不属于当前工作流");
        }

        String observedStatus = record.getStatus();
        if ("RUNNING".equals(observedStatus)) {
            throw new ExecutionConflictException("该执行正在进行中，请勿重复提交");
        }

        ResumeExecutionRequest resumeRequest = request != null ? request : new ResumeExecutionRequest();
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        List<WorkflowNode> sortedNodes = dagParser.parse(config);
        List<WorkflowEdge> edges = config.getEdges() != null ? config.getEdges() : new ArrayList<>();
        EdgeIndexes indexes = buildEdgeIndexes(edges);

        List<ExecutionSnapshot> snapshots = executionSnapshotMapper.selectByExecutionId(executionId);
        Map<String, ExecutionSnapshot> snapshotsByNode = snapshots.stream()
                .collect(Collectors.toMap(ExecutionSnapshot::getNodeId, snapshot -> snapshot, (left, right) -> right, LinkedHashMap::new));

        String startNodeId = resolveResumeStartNodeId(sortedNodes, snapshotsByNode, resumeRequest);
        if (startNodeId == null) {
            return responseFromRecord(record, snapshots);
        }

        String inputData = extractInputValue(record.getInputData());
        ResumeState resumeState = buildResumeState(
                sortedNodes,
                snapshotsByNode,
                indexes,
                startNodeId,
                Boolean.TRUE.equals(resumeRequest.getSkipSuccessNodes()),
                resumeRequest.getStartNodeId() != null && !resumeRequest.getStartNodeId().isBlank()
        );

        // 乐观锁：只有状态仍是刚才读到的值才允许接管，保证同一次执行同一时刻只有一个请求在跑
        if (executionRecordMapper.casStatus(executionId, observedStatus, "RUNNING") == 0) {
            throw new ExecutionConflictException("该执行已被其他请求接管，请刷新后重试");
        }
        record.setStatus("RUNNING");
        record.setErrorMessage(null);

        resumeState.modifiedVariables = resumeRequest.getModifiedVariables() != null
                ? new LinkedHashMap<>(resumeRequest.getModifiedVariables())
                : Collections.emptyMap();
        saveModifiedVariables(executionId, resumeState.modifiedVariables);

        long startTime = System.currentTimeMillis();
        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
        }

        return executeNodes(record, workflow.getId(), inputData, sortedNodes, indexes, resumeState, startTime, eventCallback);
    }

    private ExecutionResponse executeNodes(ExecutionRecord record,
                                           Long flowId,
                                           String inputData,
                                           List<WorkflowNode> sortedNodes,
                                           EdgeIndexes indexes,
                                           ResumeState resumeState,
                                           long startTime,
                                           Consumer<ExecutionEvent> rawEventCallback) {
        // 并发执行的节点会从不同线程回调事件，而 SseEmitter 不是线程安全的，这里统一串行化
        Consumer<ExecutionEvent> eventCallback = synchronizedCallback(rawEventCallback);
        Map<String, List<WorkflowEdge>> edgesBySource = indexes.edgesBySource;
        Map<String, List<WorkflowEdge>> edgesByTarget = indexes.edgesByTarget;

        List<ExecutionResponse.NodeResult> nodeResults = new ArrayList<>(resumeState.restoredNodeResults);
        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>(resumeState.nodeOutputs);
        Set<String> skippedNodes = new HashSet<>(resumeState.skippedNodes);

        Map<String, Object> currentInput = new HashMap<>();
        currentInput.put("input", inputData);
        currentInput.putAll(resumeState.latestOutput);
        currentInput.putAll(resumeState.modifiedVariables);

        Map<String, Integer> sortedIndex = new HashMap<>();
        for (int i = 0; i < sortedNodes.size(); i++) {
            sortedIndex.put(sortedNodes.get(i).getId(), i);
        }
        int startIndex = resumeState.startNodeId == null ? 0 : sortedIndex.getOrDefault(resumeState.startNodeId, 0);

        String status = "SUCCESS";
        String errorMessage = null;
        String outputData = null;

        try {
            // 按依赖层推进：同一层的节点互不依赖，可以并发；每层结束后再统一处理结果和条件分支路由
            for (List<WorkflowNode> level : dagParser.groupIntoLevels(sortedNodes, indexes.edges)) {
                List<WorkflowNode> runnable = new ArrayList<>();
                for (WorkflowNode node : level) {
                    if (sortedIndex.get(node.getId()) < startIndex) {
                        continue;
                    }
                    if (resumeState.restoredNodeIds.contains(node.getId())) {
                        continue;
                    }
                    if (skippedNodes.contains(node.getId())) {
                        log.info("跳过节点 [{}]（条件分支未选中）", node.getId());
                        continue;
                    }
                    runnable.add(node);
                }
                if (runnable.isEmpty()) {
                    continue;
                }

                // 本层所有节点基于"本层开始时"的上游输出快照取输入，彼此不共享可变状态
                Map<String, Map<String, Object>> outputsSnapshot = Collections.unmodifiableMap(new HashMap<>(nodeOutputs));
                Map<String, Object> fallbackSnapshot = Collections.unmodifiableMap(new HashMap<>(currentInput));
                List<NodeRun> runs = runLevel(record, flowId, runnable, outputsSnapshot, edgesByTarget,
                        fallbackSnapshot, resumeState, sortedIndex, eventCallback);

                runs.sort(Comparator.comparingInt(run -> sortedIndex.get(run.node.getId())));
                NodeRun failed = null;
                for (NodeRun run : runs) {
                    nodeResults.add(run.nodeResult);
                    if (run.error != null) {
                        if (failed == null) {
                            failed = run;
                        }
                        continue;
                    }
                    nodeOutputs.put(run.node.getId(), run.output);
                    currentInput = run.output;

                    // 条件分支路由
                    if ("condition".equals(run.node.getType())) {
                        String selectedBranch = (String) run.rawOutput.get("__selectedBranch__");
                        markSkippedNodes(run.node.getId(), selectedBranch, edgesBySource, edgesByTarget, skippedNodes);
                    }
                }
                if (failed != null) {
                    // 同层其他节点已经跑完并落了快照（续跑时可直接跳过），后面的层不再启动
                    status = "FAILED";
                    errorMessage = "节点 " + failed.node.getId() + " 执行失败: " + failed.error.getMessage();
                    break;
                }
            }

            if ("SUCCESS".equals(status)) {
                outputData = toJson(stripRuntimeFields(currentInput));
            }
        } catch (Exception e) {
            status = "FAILED";
            if (errorMessage == null) {
                errorMessage = e.getMessage();
            }
        }

        nodeResults.sort(Comparator.comparingInt(r -> sortedIndex.getOrDefault(r.getNodeId(), Integer.MAX_VALUE)));

        long endTime = System.currentTimeMillis();
        int duration = (int) (endTime - startTime);

        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowComplete(status, currentInput, duration));
        }

        record.setFlowId(flowId);
        String inputDataJson = buildInputDataJson(inputData);
        log.info("保存执行记录 - inputData: {}", inputDataJson);
        log.info("保存执行记录 - outputData: {}", outputData);
        record.setInputData(inputDataJson);
        record.setOutputData(outputData);
        record.setStatus(status);
        record.setNodeResults(toJson(nodeResults));
        record.setErrorMessage(errorMessage);
        record.setDuration(duration);
        executionRecordMapper.updateById(record);

        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(status);
        response.setInputData(inputDataJson);
        response.setNodeResults(nodeResults);
        response.setOutputData(outputData);
        response.setDuration(duration);
        response.setErrorMessage(errorMessage);

        return response;
    }

    private List<NodeRun> runLevel(ExecutionRecord record,
                                   Long flowId,
                                   List<WorkflowNode> runnable,
                                   Map<String, Map<String, Object>> outputsSnapshot,
                                   Map<String, List<WorkflowEdge>> edgesByTarget,
                                   Map<String, Object> fallbackSnapshot,
                                   ResumeState resumeState,
                                   Map<String, Integer> sortedIndex,
                                   Consumer<ExecutionEvent> eventCallback) {
        Map<WorkflowNode, Map<String, Object>> inputs = new LinkedHashMap<>();
        for (WorkflowNode node : runnable) {
            Map<String, Object> nodeInput = new LinkedHashMap<>(resolveNodeInput(node, outputsSnapshot, edgesByTarget, fallbackSnapshot));
            if (!resumeState.modifiedVariables.isEmpty()) {
                nodeInput.putAll(resumeState.modifiedVariables);
            }
            // 运行时字段：执行上下文与幂等键，供有副作用的节点（写记忆/写知识库）去重，不会被持久化
            nodeInput.put("__executionId__", record.getId());
            nodeInput.put("__idempotencyKey__", record.getId() + ":" + node.getId());
            inputs.put(node, nodeInput);
        }

        List<NodeRun> runs = new ArrayList<>();
        boolean parallel = parallelEnabled && nodePool != null && runnable.size() > 1;
        if (!parallel) {
            for (WorkflowNode node : runnable) {
                runs.add(runSingleNode(record, flowId, node, inputs.get(node), sortedIndex.get(node.getId()), eventCallback));
            }
            return runs;
        }

        log.info("并发执行 {} 个互不依赖的节点: {}", runnable.size(),
                runnable.stream().map(WorkflowNode::getId).collect(Collectors.toList()));
        List<CompletableFuture<NodeRun>> futures = new ArrayList<>();
        for (WorkflowNode node : runnable) {
            Map<String, Object> nodeInput = inputs.get(node);
            int order = sortedIndex.get(node.getId());
            futures.add(CompletableFuture.supplyAsync(
                    () -> runSingleNode(record, flowId, node, nodeInput, order, eventCallback), nodePool));
        }
        for (CompletableFuture<NodeRun> future : futures) {
            runs.add(future.join());
        }
        return runs;
    }

    /** 执行单个节点：写快照、发事件、调执行器；任何异常都收敛成结果返回，不向外抛，保证同层兄弟节点不受影响。 */
    private NodeRun runSingleNode(ExecutionRecord record,
                                  Long flowId,
                                  WorkflowNode node,
                                  Map<String, Object> nodeInput,
                                  int executionOrder,
                                  Consumer<ExecutionEvent> eventCallback) {
        long nodeStartTime = System.currentTimeMillis();
        ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
        nodeResult.setNodeId(node.getId());
        nodeResult.setNodeName(node.getType());
        nodeResult.setInput(toJson(stripRuntimeFields(nodeInput)));

        Map<String, Object> rawOutput = null;
        Map<String, Object> output = null;
        Exception error = null;
        ExecutionSnapshot snapshot = null;
        try {
            snapshot = createOrUpdateSnapshotStart(record.getId(), flowId, node, nodeInput, executionOrder, nodeStartTime);

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
            }

            NodeExecutor executor = executorFactory.getExecutor(node.getType());
            rawOutput = executor.execute(node, nodeInput, eventCallback);
            output = stripRuntimeFields(rawOutput);

            nodeResult.setStatus("SUCCESS");
            nodeResult.setOutput(toJson(output));

            long nodeEndTime = System.currentTimeMillis();
            int nodeDuration = (int) (nodeEndTime - nodeStartTime);
            nodeResult.setDuration(nodeDuration);
            markSnapshotSuccess(snapshot, output, nodeDuration, nodeEndTime);

            if (eventCallback != null) {
                Map<String, Object> eventData = new HashMap<>();
                eventData.put("input", stripRuntimeFields(nodeInput));
                eventData.put("output", output);
                eventData.put("duration", nodeDuration);
                eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), node.getType(), eventData, nodeDuration));
            }
        } catch (Exception e) {
            log.error("节点执行失败: {}", node.getId(), e);
            error = e;
            output = null;
            nodeResult.setStatus("FAILED");
            nodeResult.setOutput(null);
            nodeResult.setError(e.getMessage());
            markSnapshotFailed(snapshot, e.getMessage(), (int) (System.currentTimeMillis() - nodeStartTime));

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), e.getMessage()));
            }
        } finally {
            nodeResult.setDuration((int) (System.currentTimeMillis() - nodeStartTime));
            heartbeat(record.getId());
        }
        return new NodeRun(node, nodeResult, output, rawOutput, error);
    }

    private Consumer<ExecutionEvent> synchronizedCallback(Consumer<ExecutionEvent> callback) {
        if (callback == null) {
            return null;
        }
        Object lock = new Object();
        return event -> {
            synchronized (lock) {
                callback.accept(event);
            }
        };
    }

    @Override
    public String getEngineType() {
        return "dag";
    }
    
    /**
     * 解析节点输入：如果存在已执行的前驱节点输出，使用前驱节点输出作为输入
     */
    private Map<String, Object> resolveNodeInput(WorkflowNode node,
                                                   Map<String, Map<String, Object>> nodeOutputs,
                                                   Map<String, List<WorkflowEdge>> edgesByTarget,
                                                   Map<String, Object> fallbackInput) {
        List<WorkflowEdge> incomingEdges = edgesByTarget.get(node.getId());
        if (incomingEdges == null || incomingEdges.isEmpty()) {
            return fallbackInput;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (WorkflowEdge edge : incomingEdges) {
            Map<String, Object> sourceOutput = nodeOutputs.get(edge.getSource());
            if (sourceOutput != null) {
                resolved.putAll(sourceOutput);
            }
        }
        if (resolved.isEmpty()) {
            return fallbackInput;
        }
        resolved.put("__nodeOutputs__", nodeOutputs);
        return resolved;
    }
    
    /**
     * 标记条件分支中未选中路径的所有下游节点为跳过状态
     */
    private void markSkippedNodes(String conditionNodeId,
                                   String selectedBranch,
                                   Map<String, List<WorkflowEdge>> edgesBySource,
                                   Map<String, List<WorkflowEdge>> edgesByTarget,
                                   Set<String> skippedNodes) {
        List<WorkflowEdge> outEdges = edgesBySource.get(conditionNodeId);
        if (outEdges == null || outEdges.isEmpty()) {
            return;
        }
        
        // 判断出边是否使用了 sourceHandle（分支标识）
        boolean hasHandles = outEdges.stream().anyMatch(e -> e.getSourceHandle() != null);
        if (!hasHandles) {
            return;
        }
        
        // 收集活跃和非活跃分支的直接目标
        Set<String> activeTargets = new HashSet<>();
        Set<String> inactiveTargets = new HashSet<>();
        
        for (WorkflowEdge edge : outEdges) {
            String handle = edge.getSourceHandle();
            if (handle != null && handle.equals(selectedBranch)) {
                activeTargets.add(edge.getTarget());
            } else if (handle == null && "default".equals(selectedBranch)) {
                activeTargets.add(edge.getTarget());
            } else {
                inactiveTargets.add(edge.getTarget());
            }
        }
        
        // 递归标记非活跃分支下游节点
        for (String target : inactiveTargets) {
            if (!activeTargets.contains(target)) {
                markDownstreamSkipped(target, skippedNodes, edgesBySource, edgesByTarget);
            }
        }
    }
    
    /**
     * 递归标记下游节点为跳过。仅当节点所有入边都来自已跳过节点时才标记。
     */
    private void markDownstreamSkipped(String nodeId,
                                        Set<String> skippedNodes,
                                        Map<String, List<WorkflowEdge>> edgesBySource,
                                        Map<String, List<WorkflowEdge>> edgesByTarget) {
        if (skippedNodes.contains(nodeId)) {
            return;
        }
        
        // 检查是否所有入边的源节点都已被跳过（或尚无输出）
        List<WorkflowEdge> incomingEdges = edgesByTarget.get(nodeId);
        if (incomingEdges != null && incomingEdges.size() > 1) {
            for (WorkflowEdge edge : incomingEdges) {
                if (!skippedNodes.contains(edge.getSource())) {
                    // 有活跃路径可达此节点，不跳过
                    return;
                }
            }
        }
        
        skippedNodes.add(nodeId);
        log.info("标记节点 [{}] 为跳过（条件分支未选中路径）", nodeId);
        
        // 递归标记下游
        List<WorkflowEdge> outEdges = edgesBySource.get(nodeId);
        if (outEdges != null) {
            for (WorkflowEdge edge : outEdges) {
                markDownstreamSkipped(edge.getTarget(), skippedNodes, edgesBySource, edgesByTarget);
            }
        }
    }

    private ExecutionRecord createRunningRecord(Long flowId, String inputData) {
        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(flowId);
        record.setInputData(buildInputDataJson(inputData));
        record.setStatus("RUNNING");
        record.setNodeResults("[]");
        record.setDuration(0);
        executionRecordMapper.insert(record);
        return record;
    }

    private EdgeIndexes buildEdgeIndexes(List<WorkflowEdge> edges) {
        Map<String, List<WorkflowEdge>> edgesBySource = new HashMap<>();
        Map<String, List<WorkflowEdge>> edgesByTarget = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            edgesBySource.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge);
            edgesByTarget.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge);
        }
        return new EdgeIndexes(edges, edgesBySource, edgesByTarget);
    }

    private ExecutionSnapshot createOrUpdateSnapshotStart(Long executionId,
                                                           Long flowId,
                                                           WorkflowNode node,
                                                           Map<String, Object> nodeInput,
                                                           int executionOrder,
                                                           long startedAtMillis) {
        if (executionSnapshotMapper == null || executionId == null) {
            return null;
        }

        ExecutionSnapshot snapshot = executionSnapshotMapper.selectByExecutionIdAndNodeId(executionId, node.getId());
        if (snapshot == null) {
            snapshot = new ExecutionSnapshot();
            snapshot.setExecutionId(executionId);
            snapshot.setFlowId(flowId);
            snapshot.setNodeId(node.getId());
            snapshot.setRetryCount(0);
            applySnapshotStart(snapshot, node, nodeInput, executionOrder);
            try {
                executionSnapshotMapper.insert(snapshot);
                return snapshot;
            } catch (DuplicateKeyException e) {
                // 唯一约束 (execution_id, node_id) 兜底：并发下别人先插入了，改为更新那一条
                snapshot = executionSnapshotMapper.selectByExecutionIdAndNodeId(executionId, node.getId());
                if (snapshot == null) {
                    throw e;
                }
            }
        }

        snapshot.setRetryCount(snapshot.getRetryCount() == null ? 1 : snapshot.getRetryCount() + 1);
        applySnapshotStart(snapshot, node, nodeInput, executionOrder);
        executionSnapshotMapper.updateById(snapshot);
        return snapshot;
    }

    private void applySnapshotStart(ExecutionSnapshot snapshot,
                                    WorkflowNode node,
                                    Map<String, Object> nodeInput,
                                    int executionOrder) {
        snapshot.setNodeType(node.getType());
        snapshot.setNodeName(resolveNodeName(node));
        snapshot.setExecutionOrder(executionOrder);
        snapshot.setStatus("RUNNING");
        snapshot.setInputData(toJson(stripRuntimeFields(nodeInput)));
        snapshot.setOutputData(null);
        snapshot.setErrorMessage(null);
        snapshot.setStartedAt(LocalDateTime.now());
        snapshot.setCompletedAt(null);
        snapshot.setDuration(null);
    }

    private void heartbeat(Long executionId) {
        if (executionId == null) {
            return;
        }
        try {
            executionRecordMapper.touch(executionId);
        } catch (Exception e) {
            log.debug("刷新执行心跳失败: {}", executionId, e);
        }
    }

    private void markSnapshotSuccess(ExecutionSnapshot snapshot,
                                     Map<String, Object> output,
                                     int duration,
                                     long completedAtMillis) {
        if (executionSnapshotMapper == null || snapshot == null || snapshot.getId() == null) {
            return;
        }
        snapshot.setStatus("SUCCESS");
        snapshot.setOutputData(toJson(stripRuntimeFields(output)));
        snapshot.setErrorMessage(null);
        snapshot.setCompletedAt(LocalDateTime.now());
        snapshot.setDuration(duration);
        executionSnapshotMapper.updateById(snapshot);
    }

    private void markSnapshotFailed(ExecutionSnapshot snapshot, String errorMessage, int duration) {
        if (executionSnapshotMapper == null || snapshot == null || snapshot.getId() == null) {
            return;
        }
        snapshot.setStatus("FAILED");
        snapshot.setErrorMessage(errorMessage);
        snapshot.setCompletedAt(LocalDateTime.now());
        snapshot.setDuration(duration);
        executionSnapshotMapper.updateById(snapshot);
    }

    private String resolveResumeStartNodeId(List<WorkflowNode> sortedNodes,
                                            Map<String, ExecutionSnapshot> snapshotsByNode,
                                            ResumeExecutionRequest request) {
        if (request.getStartNodeId() != null && !request.getStartNodeId().isBlank()) {
            String requested = request.getStartNodeId();
            if (sortedNodes.stream().noneMatch(node -> node.getId().equals(requested))) {
                throw new IllegalArgumentException("指定的起始节点不存在: " + requested);
            }
            return requested;
        }

        for (WorkflowNode node : sortedNodes) {
            ExecutionSnapshot snapshot = snapshotsByNode.get(node.getId());
            if (snapshot != null && "FAILED".equals(snapshot.getStatus())) {
                return node.getId();
            }
        }

        for (WorkflowNode node : sortedNodes) {
            ExecutionSnapshot snapshot = snapshotsByNode.get(node.getId());
            if (snapshot == null || (!"SUCCESS".equals(snapshot.getStatus()) && !"SKIPPED".equals(snapshot.getStatus()))) {
                return node.getId();
            }
        }

        return null;
    }

    private ResumeState buildResumeState(List<WorkflowNode> sortedNodes,
                                         Map<String, ExecutionSnapshot> snapshotsByNode,
                                         EdgeIndexes indexes,
                                         String startNodeId,
                                         boolean skipSuccessNodes,
                                         boolean explicitStart) {
        ResumeState state = new ResumeState();
        state.startNodeId = startNodeId;
        if (!skipSuccessNodes) {
            return state;
        }

        int startIndex = 0;
        for (int i = 0; i < sortedNodes.size(); i++) {
            if (sortedNodes.get(i).getId().equals(startNodeId)) {
                startIndex = i;
                break;
            }
        }

        for (int i = 0; i < sortedNodes.size(); i++) {
            WorkflowNode node = sortedNodes.get(i);
            ExecutionSnapshot snapshot = snapshotsByNode.get(node.getId());
            if (snapshot == null || !"SUCCESS".equals(snapshot.getStatus())) {
                continue;
            }
            // 用户明确指定了起始节点：从它开始（含它及之后）的节点都要重跑；
            // 没指定时起始点是第一个失败节点，同一层里已经成功的兄弟节点（并发执行留下的）直接复用，不重复执行
            if (i >= startIndex && explicitStart) {
                continue;
            }

            Map<String, Object> output = parseJsonObject(snapshot.getOutputData());
            state.nodeOutputs.put(node.getId(), output);
            state.restoredNodeIds.add(node.getId());
            state.latestOutput = output;
            state.restoredNodeResults.add(nodeResultFromSnapshot(snapshot));

            if ("condition".equals(node.getType())) {
                String selectedBranch = output.get("__selectedBranch__") instanceof String
                        ? (String) output.get("__selectedBranch__")
                        : "default";
                markSkippedNodes(node.getId(), selectedBranch, indexes.edgesBySource, indexes.edgesByTarget, state.skippedNodes);
            }
        }

        return state;
    }

    private ExecutionResponse responseFromRecord(ExecutionRecord record, List<ExecutionSnapshot> snapshots) {
        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(record.getStatus());
        response.setInputData(record.getInputData());
        response.setOutputData(record.getOutputData());
        response.setDuration(record.getDuration());
        response.setErrorMessage(record.getErrorMessage());

        List<ExecutionResponse.NodeResult> nodeResults;
        if (record.getNodeResults() != null && !record.getNodeResults().isBlank()) {
            nodeResults = JSON.parseArray(record.getNodeResults(), ExecutionResponse.NodeResult.class);
        } else {
            nodeResults = snapshots.stream().map(this::nodeResultFromSnapshot).collect(Collectors.toList());
        }
        response.setNodeResults(nodeResults);
        return response;
    }

    private ExecutionResponse.NodeResult nodeResultFromSnapshot(ExecutionSnapshot snapshot) {
        ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
        nodeResult.setNodeId(snapshot.getNodeId());
        nodeResult.setNodeName(snapshot.getNodeType());
        nodeResult.setStatus(snapshot.getStatus());
        nodeResult.setInput(snapshot.getInputData());
        nodeResult.setOutput(snapshot.getOutputData());
        nodeResult.setDuration(snapshot.getDuration() != null ? snapshot.getDuration() : 0);
        nodeResult.setError(snapshot.getErrorMessage());
        return nodeResult;
    }

    private void saveModifiedVariables(Long executionId, Map<String, Object> modifiedVariables) {
        if (executionVariableMapper == null || executionId == null || modifiedVariables == null || modifiedVariables.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : modifiedVariables.entrySet()) {
            ExecutionVariable variable = executionVariableMapper.selectByExecutionIdAndVariableName(executionId, entry.getKey());
            if (variable == null) {
                variable = new ExecutionVariable();
                variable.setExecutionId(executionId);
                variable.setVariableName(entry.getKey());
                variable.setVariableType(resolveVariableType(entry.getValue()));
                variable.setVariableValue(toJson(entry.getValue()));
                variable.setIsModified(1);
                executionVariableMapper.insert(variable);
            } else {
                variable.setVariableType(resolveVariableType(entry.getValue()));
                variable.setVariableValue(toJson(entry.getValue()));
                variable.setIsModified(1);
                executionVariableMapper.updateById(variable);
            }
        }
    }

    private String resolveVariableType(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Map || value instanceof List) {
            return "JSON";
        }
        return "STRING";
    }

    private String resolveNodeName(WorkflowNode node) {
        if (node.getData() != null) {
            Object label = node.getData().get("label");
            if (label instanceof String && !((String) label).isBlank()) {
                return (String) label;
            }
        }
        return node.getType();
    }

    private String buildInputDataJson(String inputData) {
        Map<String, Object> inputDataMap = new HashMap<>();
        inputDataMap.put("input", inputData);
        return toJson(inputDataMap);
    }

    private String extractInputValue(String inputDataJson) {
        Map<String, Object> input = parseJsonObject(inputDataJson);
        Object inputValue = input.get("input");
        return inputValue != null ? String.valueOf(inputValue) : "";
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.parseObject(json));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        return JSON.toJSONString(stripRuntimeValue(value, new IdentityHashMap<>()));
    }

    private Map<String, Object> stripRuntimeFields(Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> stripped = (Map<String, Object>) stripRuntimeValue(value, new IdentityHashMap<>());
        return stripped;
    }

    private Object stripRuntimeValue(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (seen.containsKey(value)) {
            return "[Circular]";
        }

        if (value instanceof Map<?, ?> map) {
            seen.put(value, Boolean.TRUE);
            Map<String, Object> stripped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (RUNTIME_ONLY_KEYS.contains(key)) {
                    continue;
                }
                stripped.put(key, stripRuntimeValue(entry.getValue(), seen));
            }
            seen.remove(value);
            return stripped;
        }

        if (value instanceof List<?> list) {
            seen.put(value, Boolean.TRUE);
            List<Object> stripped = new ArrayList<>();
            for (Object item : list) {
                stripped.add(stripRuntimeValue(item, seen));
            }
            seen.remove(value);
            return stripped;
        }

        return value;
    }

    private static class EdgeIndexes {
        private final List<WorkflowEdge> edges;
        private final Map<String, List<WorkflowEdge>> edgesBySource;
        private final Map<String, List<WorkflowEdge>> edgesByTarget;

        private EdgeIndexes(List<WorkflowEdge> edges,
                            Map<String, List<WorkflowEdge>> edgesBySource,
                            Map<String, List<WorkflowEdge>> edgesByTarget) {
            this.edges = edges;
            this.edgesBySource = edgesBySource;
            this.edgesByTarget = edgesByTarget;
        }
    }

    /** 单个节点一次执行的结果（成功带 output，失败带 error） */
    private static class NodeRun {
        private final WorkflowNode node;
        private final ExecutionResponse.NodeResult nodeResult;
        private final Map<String, Object> output;
        private final Map<String, Object> rawOutput;
        private final Exception error;

        private NodeRun(WorkflowNode node,
                        ExecutionResponse.NodeResult nodeResult,
                        Map<String, Object> output,
                        Map<String, Object> rawOutput,
                        Exception error) {
            this.node = node;
            this.nodeResult = nodeResult;
            this.output = output;
            this.rawOutput = rawOutput;
            this.error = error;
        }
    }

    private static class ResumeState {
        private String startNodeId;
        private final Set<String> restoredNodeIds = new HashSet<>();
        private final List<ExecutionResponse.NodeResult> restoredNodeResults = new ArrayList<>();
        private final Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        private final Set<String> skippedNodes = new HashSet<>();
        private Map<String, Object> latestOutput = new HashMap<>();
        private Map<String, Object> modifiedVariables = Collections.emptyMap();
    }
}
