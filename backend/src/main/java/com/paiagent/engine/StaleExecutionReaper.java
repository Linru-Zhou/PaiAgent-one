package com.paiagent.engine;

import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 僵尸执行回收：进程重启或崩溃会让执行记录永远停在 RUNNING，
 * 定时把"长时间没有心跳"的 RUNNING 记录置为 FAILED，之后用户就可以从断点续跑。
 */
@Slf4j
@Component
public class StaleExecutionReaper {

    static final String MESSAGE = "执行长时间无心跳（疑似服务重启或进程中断），已自动标记为失败，可从断点继续执行";
    static final String CANCELLED_MESSAGE = "执行已取消（取消期间服务中断，已自动收尾）";

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired(required = false)
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Value("${paiagent.execution.stale-after-minutes:30}")
    private long staleAfterMinutes;

    @Scheduled(fixedDelayString = "${paiagent.execution.reaper-interval-ms:60000}",
            initialDelayString = "${paiagent.execution.reaper-interval-ms:60000}")
    public void scheduledReap() {
        reapStale(Duration.ofMinutes(staleAfterMinutes));
    }

    /** @return 本次回收的执行数量 */
    public int reapStale(Duration staleAfter) {
        LocalDateTime threshold = LocalDateTime.now().minus(staleAfter);
        List<Long> staleIds = executionRecordMapper.selectStaleRunningIds(threshold);
        int reaped = 0;
        for (Long id : staleIds) {
            if (executionRecordMapper.failIfRunning(id, MESSAGE) > 0) {
                reaped++;
                if (executionSnapshotMapper != null) {
                    executionSnapshotMapper.failRunningByExecutionId(id, MESSAGE);
                }
                log.warn("回收僵尸执行 #{}", id);
            }
        }
        // 取消已受理但节点一直没退出、之后又没有心跳：同样是进程中断留下的，收尾成 CANCELLED
        for (Long id : executionRecordMapper.selectStaleCancellingIds(threshold)) {
            if (executionRecordMapper.cancelIfCancelling(id, CANCELLED_MESSAGE) > 0) {
                reaped++;
                if (executionSnapshotMapper != null) {
                    executionSnapshotMapper.cancelRunningByExecutionId(id, CANCELLED_MESSAGE);
                }
                log.warn("回收取消中的僵尸执行 #{}", id);
            }
        }
        return reaped;
    }
}
