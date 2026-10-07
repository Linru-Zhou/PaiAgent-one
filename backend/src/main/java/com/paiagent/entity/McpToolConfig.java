package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.paiagent.security.EncryptedEnvTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "mcp_tool_config", autoResultMap = true)
public class McpToolConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String toolType;

    private String toolName;

    private String transport;

    private String command;

    private String args;

    @TableField(typeHandler = EncryptedEnvTypeHandler.class)
    private String env;

    private Integer enabled;

    private Integer preset;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
