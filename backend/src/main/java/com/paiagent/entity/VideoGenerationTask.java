package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频生成任务检查点：每个"执行 × 节点"最多一行，记录最近一次提交给服务商的任务。
 * 轮询超时或进程中断后，断点续跑据此找回仍在生成的任务，而不是重新提交、重复计费。
 */
@Data
@TableName("video_generation_task")
public class VideoGenerationTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键（executionId:nodeId） */
    private String idempotencyKey;

    /** 服务商返回的任务 ID */
    private String taskId;

    /** 请求指纹（模型、prompt、参考图、时长、分辨率、比例、镜头），参数变了就不能复用旧任务 */
    private String fingerprint;

    /** PENDING / SUCCEEDED / FAILED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
