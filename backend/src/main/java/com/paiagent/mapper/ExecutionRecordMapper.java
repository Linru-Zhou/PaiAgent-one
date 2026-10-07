package com.paiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.paiagent.entity.ExecutionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行记录 Mapper 接口
 */
@Mapper
public interface ExecutionRecordMapper extends BaseMapper<ExecutionRecord> {

    /**
     * 带条件的状态流转（乐观锁）：只有当前状态仍是 expected 才会更新，返回受影响行数。
     * 用来保证同一次执行同一时刻只会被一个请求接管。
     */
    @Update("UPDATE execution_record SET status = #{target}, error_message = NULL "
            + "WHERE id = #{id} AND status = #{expected} AND deleted = 0")
    int casStatus(@Param("id") Long id, @Param("expected") String expected, @Param("target") String target);

    /** 执行心跳：仅对 RUNNING 记录刷新 updated_at。 */
    @Update("UPDATE execution_record SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'RUNNING'")
    int touch(@Param("id") Long id);

    /** 查询超过阈值没有心跳的 RUNNING 记录（疑似进程中断留下的僵尸执行）。 */
    @Select("SELECT id FROM execution_record WHERE status = 'RUNNING' AND deleted = 0 AND updated_at < #{threshold}")
    List<Long> selectStaleRunningIds(@Param("threshold") LocalDateTime threshold);

    /** 仅当仍为 RUNNING 时置为 FAILED，避免覆盖刚刚正常结束的执行。 */
    @Update("UPDATE execution_record SET status = 'FAILED', error_message = #{message} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int failIfRunning(@Param("id") Long id, @Param("message") String message);
}
