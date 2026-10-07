package com.paiagent.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;

public final class TestDb {

    private TestDb() {
    }

    /** 删除某个测试工作流产生的全部执行记录与快照（物理删除），避免污染本地开发库。 */
    public static void deleteExecutions(long flowId, ExecutionRecordMapper recordMapper, ExecutionSnapshotMapper snapshotMapper) {
        for (ExecutionRecord record : recordMapper.selectList(
                new LambdaQueryWrapper<ExecutionRecord>().eq(ExecutionRecord::getFlowId, flowId))) {
            snapshotMapper.deleteByExecutionId(record.getId());
            recordMapper.deleteById(record.getId());
        }
    }
}
