package com.paiagent.engine;

import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.paiagent.engine.support.ExecutionSupport.stripRuntimeFields;
import static com.paiagent.engine.support.ExecutionSupport.toJson;

/**
 * 执行记录、节点快照、心跳的统一落库入口。两套引擎都通过它写库，
 * 因此"执行中就有记录、每个节点都有快照、失败可以断点续跑"对两套引擎同样成立。
 */
@Slf4j
@Component
public class ExecutionRecorder {

    private final ExecutionRecordMapper executionRecordMapper;
    private final ExecutionSnapshotMapper executionSnapshotMapper;

    /** 执行结束后把结果和产物对应关系写到可见的输出文件夹；可选，测试里手动构造时没有。 */
    @Autowired(required = false)
    private com.paiagent.service.ExecutionResultExporter resultExporter;

    @Autowired
    public ExecutionRecorder(ExecutionRecordMapper executionRecordMapper,
                             @Autowired(required = false) ExecutionSnapshotMapper executionSnapshotMapper) {
        this.executionRecordMapper = executionRecordMapper;
        this.executionSnapshotMapper = executionSnapshotMapper;
    }

    public ExecutionRecord start(Long flowId, String inputData) {
        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(flowId);
        record.setInputData(buildInputDataJson(inputData));
        record.setStatus("RUNNING");
        record.setNodeResults("[]");
        record.setDuration(0);
        executionRecordMapper.insert(record);
        return record;
    }

    public void finish(ExecutionRecord record, Long flowId, String inputData, String status, String outputData,
                       String nodeResultsJson, String errorMessage, int duration) {
        record.setFlowId(flowId);
        record.setInputData(buildInputDataJson(inputData));
        record.setOutputData(outputData);
        record.setStatus(status);
        record.setNodeResults(nodeResultsJson);
        record.setErrorMessage(errorMessage);
        record.setDuration(duration);
        executionRecordMapper.updateById(record);
        if (resultExporter != null) {
            resultExporter.export(record.getId(), flowId, status, inputData, outputData, nodeResultsJson, errorMessage, duration);
        }
    }

    public void heartbeat(Long executionId) {
        if (executionId == null) {
            return;
        }
        try {
            executionRecordMapper.touch(executionId);
        } catch (Exception e) {
            log.debug("刷新执行心跳失败: {}", executionId, e);
        }
    }

    public ExecutionSnapshot nodeStarted(Long executionId, Long flowId, WorkflowNode node,
                                         Map<String, Object> nodeInput, int executionOrder) {
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
            applyStart(snapshot, node, nodeInput, executionOrder);
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
        applyStart(snapshot, node, nodeInput, executionOrder);
        executionSnapshotMapper.updateById(snapshot);
        return snapshot;
    }

    public void nodeSucceeded(ExecutionSnapshot snapshot, Map<String, Object> output, int duration) {
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

    public void nodeFailed(ExecutionSnapshot snapshot, String errorMessage, int duration) {
        if (executionSnapshotMapper == null || snapshot == null || snapshot.getId() == null) {
            return;
        }
        snapshot.setStatus("FAILED");
        snapshot.setErrorMessage(errorMessage);
        snapshot.setCompletedAt(LocalDateTime.now());
        snapshot.setDuration(duration);
        executionSnapshotMapper.updateById(snapshot);
    }

    /** 节点被取消中断：不是它自己出错，所以单独一个状态；续跑时和失败节点一样会被重新执行。 */
    public void nodeCancelled(ExecutionSnapshot snapshot, String message, int duration) {
        if (executionSnapshotMapper == null || snapshot == null || snapshot.getId() == null) {
            return;
        }
        snapshot.setStatus("CANCELLED");
        snapshot.setErrorMessage(message);
        snapshot.setCompletedAt(LocalDateTime.now());
        snapshot.setDuration(duration);
        executionSnapshotMapper.updateById(snapshot);
    }

    private void applyStart(ExecutionSnapshot snapshot, WorkflowNode node, Map<String, Object> nodeInput, int executionOrder) {
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

    private String resolveNodeName(WorkflowNode node) {
        if (node.getData() != null) {
            Object label = node.getData().get("label");
            if (label instanceof String && !((String) label).isBlank()) {
                return (String) label;
            }
        }
        return node.getType();
    }

    public static String buildInputDataJson(String inputData) {
        Map<String, Object> inputDataMap = new HashMap<>();
        inputDataMap.put("input", inputData);
        return toJson(inputDataMap);
    }

    public List<ExecutionSnapshot> snapshots(Long executionId) {
        return executionSnapshotMapper == null ? List.of() : executionSnapshotMapper.selectByExecutionId(executionId);
    }
}
