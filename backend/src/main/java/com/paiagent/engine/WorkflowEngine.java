package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.NodeRunner.NodeRun;
import com.paiagent.engine.cancel.ExecutionCancellations;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.support.ExecutionSupport;
import com.paiagent.engine.support.ExecutionSupport.EdgeIndexes;
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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.paiagent.engine.support.ExecutionSupport.parseJsonObject;
import static com.paiagent.engine.support.ExecutionSupport.stripRuntimeFields;
import static com.paiagent.engine.support.ExecutionSupport.toJson;

/**
 * DAG 引擎：自研的拓扑排序 + 按依赖层并发调度。
 * 节点输入解析、条件路由、单节点执行/快照/事件都复用 {@link ExecutionSupport}、{@link NodeRunner}、{@link ExecutionRecorder}，
 * 与 LangGraph 引擎共用同一份实现。
 */
@Slf4j
@Service
public class WorkflowEngine implements WorkflowExecutor {

    @Autowired
    private DAGParser dagParser;

    @Autowired
    private WorkflowConfigParser workflowConfigParser;

    @Autowired
    private ExecutionRecorder recorder;

    @Autowired
    private NodeRunner nodeRunner;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired(required = false)
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Autowired(required = false)
    private ExecutionVariableMapper executionVariableMapper;

    /** 取消登记表；单测里手工构造时没有，此时不支持取消。 */
    @Autowired(required = false)
    private ExecutionCancellations cancellations;

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
        EdgeIndexes indexes = EdgeIndexes.of(edges);
        ExecutionRecord record = recorder.start(workflow.getId(), inputData);

        return executeNodes(record, workflow.getId(), inputData, sortedNodes, indexes, new ResumeState(), startTime, eventCallback);
    }

    /**
     * 断点续执行。快照是引擎无关的，所以不管这次执行最初是 DAG 引擎还是 LangGraph 引擎跑的，都可以用这里续跑。
     */
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
        if ("CANCELLING".equals(observedStatus)) {
            throw new ExecutionConflictException("该执行正在取消中，等它变为已取消后再续跑");
        }

        ResumeExecutionRequest resumeRequest = request != null ? request : new ResumeExecutionRequest();
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        List<WorkflowNode> sortedNodes = dagParser.parse(config);
        List<WorkflowEdge> edges = config.getEdges() != null ? config.getEdges() : new ArrayList<>();
        EdgeIndexes indexes = EdgeIndexes.of(edges);

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

        return executeNodes(record, workflow.getId(), inputData, sortedNodes, indexes, resumeState, startTime, eventCallback);
    }

    /**
     * 登记取消标记再执行：登记表里有它，取消请求才找得到这次执行。
     * 注销放在落库之后，这样"登记表里没有"只可能是"已经结束"或者"不在本进程里"。
     */
    private ExecutionResponse executeNodes(ExecutionRecord record,
                                           Long flowId,
                                           String inputData,
                                           List<WorkflowNode> sortedNodes,
                                           EdgeIndexes indexes,
                                           ResumeState resumeState,
                                           long startTime,
                                           Consumer<ExecutionEvent> rawEventCallback) {
        if (cancellations != null) {
            cancellations.register(record.getId());
        }
        try {
            // 先登记再对外公布执行编号（WORKFLOW_START 事件）：客户端拿到编号后立刻取消，也一定找得到这次执行
            if (rawEventCallback != null) {
                rawEventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
            }
            return runNodes(record, flowId, inputData, sortedNodes, indexes, resumeState, startTime, rawEventCallback);
        } finally {
            if (cancellations != null) {
                cancellations.unregister(record.getId());
            }
        }
    }

    private ExecutionResponse runNodes(ExecutionRecord record,
                                       Long flowId,
                                       String inputData,
                                       List<WorkflowNode> sortedNodes,
                                       EdgeIndexes indexes,
                                       ResumeState resumeState,
                                       long startTime,
                                       Consumer<ExecutionEvent> rawEventCallback) {
        // 并发执行的节点会从不同线程回调事件，而 SseEmitter 不是线程安全的，这里统一串行化
        Consumer<ExecutionEvent> eventCallback = synchronizedCallback(rawEventCallback);
        Map<String, List<WorkflowEdge>> edgesBySource = indexes.bySource();
        Map<String, List<WorkflowEdge>> edgesByTarget = indexes.byTarget();

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
            for (List<WorkflowNode> level : dagParser.groupIntoLevels(sortedNodes, indexes.edges())) {
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
                boolean cancelledInLevel = false;
                for (NodeRun run : runs) {
                    if (run.nodeResult != null) {
                        nodeResults.add(run.nodeResult);
                    }
                    if (run.error != null) {
                        if (run.cancelled) {
                            cancelledInLevel = true;
                        } else if (failed == null) {
                            failed = run;
                        }
                        continue;
                    }
                    nodeOutputs.put(run.node.getId(), run.output);
                    currentInput = run.output;

                    // 条件分支路由
                    if ("condition".equals(run.node.getType())) {
                        String selectedBranch = (String) run.rawOutput.get("__selectedBranch__");
                        ExecutionSupport.markSkippedNodes(run.node.getId(), selectedBranch, edgesBySource, edgesByTarget, skippedNodes);
                    }
                }
                if (failed != null) {
                    // 同层其他节点已经跑完并落了快照（续跑时可直接跳过），后面的层不再启动
                    status = "FAILED";
                    errorMessage = "节点 " + failed.node.getId() + " 执行失败: " + failed.error.getMessage();
                    break;
                }
                if (cancelledInLevel) {
                    // 真实的失败比"被取消"更有信息量，所以上面的 FAILED 优先；只有没有失败时才是 CANCELLED
                    status = "CANCELLED";
                    errorMessage = ExecutionSupport.cancelMessage(nodeResults);
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

        String inputDataJson = ExecutionRecorder.buildInputDataJson(inputData);
        log.info("保存执行记录 - inputData: {}", inputDataJson);
        log.info("保存执行记录 - outputData: {}", outputData);
        recorder.finish(record, flowId, inputData, status, outputData, toJson(nodeResults), errorMessage, duration);

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
            Map<String, Object> nodeInput = new LinkedHashMap<>(
                    ExecutionSupport.resolveNodeInput(node, outputsSnapshot, edgesByTarget, fallbackSnapshot));
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
                runs.add(nodeRunner.run(record, flowId, node, inputs.get(node), sortedIndex.get(node.getId()), eventCallback));
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
                    () -> nodeRunner.run(record, flowId, node, nodeInput, order, eventCallback), nodePool));
        }
        for (CompletableFuture<NodeRun> future : futures) {
            runs.add(future.join());
        }
        return runs;
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
                ExecutionSupport.markSkippedNodes(node.getId(), selectedBranch, indexes.bySource(), indexes.byTarget(), state.skippedNodes);
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
        nodeResult.setNodeName(snapshot.getNodeName() != null && !snapshot.getNodeName().isBlank() ? snapshot.getNodeName() : snapshot.getNodeType());
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

    private String extractInputValue(String inputDataJson) {
        Map<String, Object> input = parseJsonObject(inputDataJson);
        Object inputValue = input.get("input");
        return inputValue != null ? String.valueOf(inputValue) : "";
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
