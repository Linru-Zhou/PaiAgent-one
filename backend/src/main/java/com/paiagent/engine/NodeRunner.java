package com.paiagent.engine;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.paiagent.engine.support.ExecutionSupport.stripRuntimeFields;
import static com.paiagent.engine.support.ExecutionSupport.toJson;

/**
 * 单个节点的一次执行：写快照、发事件、调执行器、记录心跳；任何异常都收敛成结果返回，不向外抛。
 * DAG 引擎和 LangGraph 引擎都用它，所以两边的节点级行为（快照内容、事件序列、错误信息）是同一份实现。
 */
@Slf4j
@Component
public class NodeRunner {

    /** 单个节点一次执行的结果（成功带 output，失败带 error） */
    public static class NodeRun {
        public final WorkflowNode node;
        public final ExecutionResponse.NodeResult nodeResult;
        public final Map<String, Object> output;
        public final Map<String, Object> rawOutput;
        public final Exception error;

        NodeRun(WorkflowNode node, ExecutionResponse.NodeResult nodeResult,
                Map<String, Object> output, Map<String, Object> rawOutput, Exception error) {
            this.node = node;
            this.nodeResult = nodeResult;
            this.output = output;
            this.rawOutput = rawOutput;
            this.error = error;
        }
    }

    private final NodeExecutorFactory executorFactory;
    private final ExecutionRecorder recorder;

    @Autowired
    public NodeRunner(NodeExecutorFactory executorFactory, ExecutionRecorder recorder) {
        this.executorFactory = executorFactory;
        this.recorder = recorder;
    }

    public NodeRun run(ExecutionRecord record,
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
            snapshot = recorder.nodeStarted(record.getId(), flowId, node, nodeInput, executionOrder);

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
            }

            NodeExecutor executor = executorFactory.getExecutor(node.getType());
            rawOutput = executor.execute(node, nodeInput, eventCallback);
            output = stripRuntimeFields(rawOutput);

            nodeResult.setStatus("SUCCESS");
            nodeResult.setOutput(toJson(output));

            int nodeDuration = (int) (System.currentTimeMillis() - nodeStartTime);
            nodeResult.setDuration(nodeDuration);
            recorder.nodeSucceeded(snapshot, output, nodeDuration);

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
            recorder.nodeFailed(snapshot, e.getMessage(), (int) (System.currentTimeMillis() - nodeStartTime));

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), e.getMessage()));
            }
        } finally {
            nodeResult.setDuration((int) (System.currentTimeMillis() - nodeStartTime));
            recorder.heartbeat(record.getId());
        }
        return new NodeRun(node, nodeResult, output, rawOutput, error);
    }
}
