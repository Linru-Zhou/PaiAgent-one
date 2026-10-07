package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.paiagent.security.EncryptedStringTypeHandler;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 全局模型配置实体类，LLM 与 TTS 节点共用。
 */
@Data
@TableName(value = "llm_global_config", autoResultMap = true)
public class LLMGlobalConfig {

    /**
     * 配置主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提供商: openai/deepseek/qwen/step/zhipu/ai_ping
     */
    private String provider;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * API 地址
     */
    private String apiUrl;

    /**
     * API 密钥
     */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String apiKey;

    /**
     * 默认 LLM 模型
     */
    private String model;

    /**
     * 默认 TTS 模型
     */
    private String ttsModel;

    /**
     * 默认向量模型
     */
    private String embeddingModel;

    /**
     * 默认图片生成模型
     */
    private String imageModel;

    /**
     * 默认视频生成模型
     */
    private String videoModel;

    /**
     * 是否启用 Agent Plan 记忆能力
     */
    private Integer memoryEnabled;

    /**
     * 默认温度
     */
    private BigDecimal temperature;

    /**
     * 是否默认配置(0-否,1-是)
     */
    private Integer isDefault;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识
     */
    @TableLogic
    private Integer deleted;
}
