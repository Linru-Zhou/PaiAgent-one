package com.paiagent.engine;

import com.paiagent.common.StructuredNodeError;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.cancel.CancelToken;
import com.paiagent.engine.cancel.ExecutionCancellations;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import com.paiagent.engine.support.ExecutionSupport;
import static com.paiagent.engine.support.ExecutionSupport.stripRuntimeFields;
import static com.paiagent.engine.support.ExecutionSupport.toJson;

/**
 * 单个节点的一次执行：写快照、发事件、调执行器、记录心跳；任何异常都收敛成结果返回，不向外抛。
 * DAG 引擎和 LangGraph 引擎都用它，所以两边的节点级行为（快照内容、事件序列、错误信息）是同一份实现。
 *
 * <p>取消也在这里统一处理：执行已被取消就不再启动新节点；节点运行中被取消，会被中断，
 * 并且只有"确实是被取消中断的"才记成 CANCELLED，节点自己出的错仍然是 FAILED。
 */
@Slf4j
@Component
public class NodeRunner {

    public static final String CANCELLED_MESSAGE = "执行已取消";

    /** 单个节点一次执行的结果（成功带 output，失败带 error） */
    public static class NodeRun {
        public final WorkflowNode node;
        /** 没有启动（执行在它之前就已被取消）时为 null */
        public final ExecutionResponse.NodeResult nodeResult;
        public final Map<String, Object> output;
        public final Map<String, Object> rawOutput;
        public final Exception error;
        /** 这个节点没有跑完是因为执行被取消（包括根本没来得及启动），不是它自己出错 */
        public final boolean cancelled;

        NodeRun(WorkflowNode node, ExecutionResponse.NodeResult nodeResult,
                Map<String, Object> output, Map<String, Object> rawOutput, Exception error, boolean cancelled) {
            this.node = node;
            this.nodeResult = nodeResult;
            this.output = output;
            this.rawOutput = rawOutput;
            this.error = error;
            this.cancelled = cancelled;
        }
    }

    private final NodeExecutorFactory executorFactory;
    private final ExecutionRecorder recorder;

    /** 单测里手工构造时没有；没有就不支持取消。 */
    private ExecutionCancellations cancellations;

    @Autowired
    public NodeRunner(NodeExecutorFactory executorFactory, ExecutionRecorder recorder) {
        this.executorFactory = executorFactory;
        this.recorder = recorder;
    }

    @Autowired(required = false)
    public void setCancellations(ExecutionCancellations cancellations) {
        this.cancellations = cancellations;
    }

    /** 中断标志还在线程上，或者异常链里有"被中断"的异常（sleep、阻塞 IO、JDK HttpClient 等都是这样表现的）。 */
    private static boolean looksLikeInterrupt(Throwable error) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof InterruptedIOException
                    || cause instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    public NodeRun run(ExecutionRecord record,
                       Long flowId,
                       WorkflowNode node,
                       Map<String, Object> nodeInput,
                       int executionOrder,
                       Consumer<ExecutionEvent> eventCallback) {
        CancelToken token = cancellations == null ? null : cancellations.find(record.getId());
        if (token != null && token.isCancelled()) {
            log.info("执行 #{} 已取消，不再启动节点 {}", record.getId(), node.getId());
            return new NodeRun(node, null, null, null, new CancellationException(CANCELLED_MESSAGE), true);
        }

        long nodeStartTime = System.currentTimeMillis();
        ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
        nodeResult.setNodeId(node.getId());
        nodeResult.setNodeName(ExecutionSupport.displayName(node));
        nodeResult.setInput(toJson(stripRuntimeFields(nodeInput)));

        Map<String, Object> rawOutput = null;
        Map<String, Object> output = null;
        Exception error = null;
        boolean cancelled = false;
        ExecutionSnapshot snapshot = null;
        try {
            snapshot = recorder.nodeStarted(record.getId(), flowId, node, nodeInput, executionOrder);

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), ExecutionSupport.displayName(node)));
            }

            NodeExecutor executor = executorFactory.getExecutor(node.getType());
            boolean interruptedByCancel = false;
            try {
                if (token != null) {
                    token.enter();
                }
                rawOutput = executor.execute(node, nodeInput, eventCallback);
            } catch (Exception e) {
                // 必须在 exit() 之前读：exit() 会清掉这次中断的记录
                // 被取消中断过，并且这次异常确实是"被中断"造成的，才算取消。节点吞掉中断、之后因为别的原因失败的，
                // 仍然是它自己的失败，不能把真实的错误信息盖成"已取消"
                interruptedByCancel = token != null && token.interruptedByCancel() && looksLikeInterrupt(e);
                if (interruptedByCancel) {
                    cancelled = true;
                }
                throw e;
            } finally {
                if (token != null) {
                    token.exit();
                }
            }
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
                eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), ExecutionSupport.displayName(node), eventData, nodeDuration));
            }
        } catch (Exception e) {
            output = null;
            nodeResult.setOutput(null);
            if (cancelled) {
                log.info("节点被取消: {}", node.getId());
                error = new CancellationException(CANCELLED_MESSAGE);
                nodeResult.setStatus("CANCELLED");
                nodeResult.setError(CANCELLED_MESSAGE);
                recorder.nodeCancelled(snapshot, CANCELLED_MESSAGE, (int) (System.currentTimeMillis() - nodeStartTime));
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeError(node.getId(), ExecutionSupport.displayName(node), CANCELLED_MESSAGE));
                }
            } else {
                log.error("节点执行失败: {}", node.getId(), e);
                error = e;
                nodeResult.setStatus("FAILED");
                nodeResult.setError(e.getMessage());
                if (e instanceof StructuredNodeError structured) {
                    nodeResult.setErrorCode(structured.errorCode());
                    nodeResult.setErrorDetail(structured.errorDetail());
                }
                recorder.nodeFailed(snapshot, e.getMessage(), (int) (System.currentTimeMillis() - nodeStartTime));

                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeError(node.getId(), ExecutionSupport.displayName(node), e.getMessage()));
                }
            }
        } finally {
            nodeResult.setDuration((int) (System.currentTimeMillis() - nodeStartTime));
            recorder.heartbeat(record.getId());
        }
        return new NodeRun(node, nodeResult, output, rawOutput, error, cancelled);
    }
}
