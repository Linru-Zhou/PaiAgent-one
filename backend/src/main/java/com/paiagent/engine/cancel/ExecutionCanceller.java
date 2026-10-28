package com.paiagent.engine.cancel;

import com.paiagent.engine.ExecutionConflictException;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 取消一次执行。协作式取消：
 *
 * <ol>
 *   <li>状态用乐观锁流转 {@code RUNNING → CANCELLING}（只有仍是 RUNNING 才能进入，和续跑接管同一种保护）；</li>
 *   <li>取消标记置位并中断正在干活的节点线程；引擎在层与层之间看到标记就停止启动新节点；</li>
 *   <li>引擎收尾时把状态写成 {@code CANCELLED}。如果取消来得太晚（节点全跑完了），结果仍是 SUCCESS，
 *       因为取消的含义是"别再往下做"，而不是"丢掉已经做完的结果"。</li>
 * </ol>
 * 已成功完成的节点快照保留，所以取消后仍然可以 {@code resume} 从断点继续。
 *
 * <p>局限（要如实讲）：节点被中断后能多快退出，取决于它在做什么。{@code sleep}、轮询供应商这类可中断的马上退出；
 * 阻塞在不响应中断的网络读上的，要等它自己的超时。这段时间状态停在 {@code CANCELLING}，而不是假装已经取消。
 * 已经提交给供应商的付费任务（比如视频）供应商那边不会被撤销，续跑时用"继续等待 / 重新生成"处理。
 */
@Slf4j
@Service
public class ExecutionCanceller {

    public static final String ORPHAN_MESSAGE = "执行已取消（当时没有任何进程在运行它，疑似服务重启留下的记录）";

    /** @param status CANCELLING（已受理，节点正在退出）或 CANCELLED */
    public record Outcome(Long executionId, String status, String message) {
    }

    private final ExecutionRecordMapper recordMapper;
    private final ExecutionCancellations cancellations;
    private final ExecutionSnapshotMapper snapshotMapper;

    @Autowired
    public ExecutionCanceller(ExecutionRecordMapper recordMapper,
                              ExecutionCancellations cancellations,
                              @Autowired(required = false) ExecutionSnapshotMapper snapshotMapper) {
        this.recordMapper = recordMapper;
        this.cancellations = cancellations;
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * @throws IllegalArgumentException   执行记录不存在，或不属于该工作流
     * @throws ExecutionConflictException 执行已经结束（SUCCESS / FAILED），没有可取消的
     */
    public Outcome cancel(Long flowId, Long executionId) {
        ExecutionRecord record = recordMapper.selectById(executionId);
        if (record == null || !Objects.equals(record.getFlowId(), flowId)) {
            throw new IllegalArgumentException("执行记录不存在");
        }
        String status = record.getStatus();

        if ("CANCELLED".equals(status)) {
            return new Outcome(executionId, "CANCELLED", "这次执行已经是取消状态");
        }
        if ("CANCELLING".equals(status)) {
            signal(executionId); // 重复请求幂等：再通知一次还在干活的线程
            return new Outcome(executionId, "CANCELLING", "已经在取消中，节点正在退出");
        }
        if (!"RUNNING".equals(status)) {
            throw new ExecutionConflictException("该执行已结束（状态: " + status + "），没有可以取消的内容");
        }

        CancelToken token = cancellations.find(executionId);
        if (token == null) {
            return cancelOrphan(executionId);
        }
        if (recordMapper.requestCancel(executionId) == 0) {
            // 刚好在这一刻结束或被别的请求处理了：以最新状态为准
            return cancel(flowId, executionId);
        }
        token.cancel();
        log.info("已受理取消: 执行 #{}", executionId);
        return new Outcome(executionId, "CANCELLING", "已受理取消，正在中断运行中的节点；用 get_execution 查看最终状态");
    }

    private Outcome cancelOrphan(Long executionId) {
        if (recordMapper.cancelOrphan(executionId, ORPHAN_MESSAGE) == 0) {
            // 登记表里没有、但状态也不再是 RUNNING：刚好结束了，重新判断
            ExecutionRecord latest = recordMapper.selectById(executionId);
            String latestStatus = latest == null ? "未知" : latest.getStatus();
            if ("CANCELLED".equals(latestStatus) || "CANCELLING".equals(latestStatus)) {
                return new Outcome(executionId, latestStatus, "已经在取消");
            }
            throw new ExecutionConflictException("该执行已结束（状态: " + latestStatus + "），没有可以取消的内容");
        }
        if (snapshotMapper != null) {
            snapshotMapper.cancelRunningByExecutionId(executionId, ORPHAN_MESSAGE);
        }
        log.warn("取消了一个没有进程在运行的执行 #{}", executionId);
        return new Outcome(executionId, "CANCELLED", ORPHAN_MESSAGE);
    }

    private void signal(Long executionId) {
        CancelToken token = cancellations.find(executionId);
        if (token != null) {
            token.cancel();
        }
    }
}
