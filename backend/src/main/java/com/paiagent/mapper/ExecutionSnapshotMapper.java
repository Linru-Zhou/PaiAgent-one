package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.ExecutionSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/**
 * 执行快照 Mapper 接口
 */
@Mapper
public interface ExecutionSnapshotMapper extends BaseMapper<ExecutionSnapshot> {
    
    /**
     * 根据执行记录 ID 查询快照列表
     */
    List<ExecutionSnapshot> selectByExecutionId(@Param("executionId") Long executionId);
    
    /**
     * 根据执行记录 ID 和节点 ID 查询快照
     */
    ExecutionSnapshot selectByExecutionIdAndNodeId(
        @Param("executionId") Long executionId, 
        @Param("nodeId") String nodeId
    );
    
    /** 把某次执行里仍处于 RUNNING 的节点快照标记为 FAILED（用于回收僵尸执行）。 */
    @Update("UPDATE execution_snapshot SET status = 'FAILED', error_message = #{message}, completed_at = CURRENT_TIMESTAMP "
            + "WHERE execution_id = #{executionId} AND status = 'RUNNING'")
    int failRunningByExecutionId(@Param("executionId") Long executionId, @Param("message") String message);

    /** 把某次执行里仍处于 RUNNING 的节点快照标记为 CANCELLED（取消一个没有进程在跑的僵尸执行时用）。 */
    @Update("UPDATE execution_snapshot SET status = 'CANCELLED', error_message = #{message}, completed_at = CURRENT_TIMESTAMP "
            + "WHERE execution_id = #{executionId} AND status = 'RUNNING'")
    int cancelRunningByExecutionId(@Param("executionId") Long executionId, @Param("message") String message);

    /**
     * 根据执行记录 ID 删除快照
     */
    int deleteByExecutionId(@Param("executionId") Long executionId);
}