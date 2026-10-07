package com.paiagent.config;

import com.paiagent.security.SecretCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies small idempotent schema upgrades for local/dev databases that were created before new features.
 */
@Slf4j
@Component
@Order(0)
public class SchemaMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SecretCodec secretCodec;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, SecretCodec secretCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCodec = secretCodec;
    }

    @Override
    public void run(ApplicationArguments args) {
        createTableIfMissing("knowledge_base", """
                CREATE TABLE knowledge_base (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识库 ID',
                    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '知识库描述',
                    config_id BIGINT DEFAULT NULL COMMENT 'Agent Plan 全局配置 ID',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    chunk_size INT DEFAULT 800 COMMENT '分片长度',
                    chunk_overlap INT DEFAULT 100 COMMENT '分片重叠长度',
                    status VARCHAR(30) DEFAULT 'DRAFT' COMMENT '状态',
                    document_count INT DEFAULT 0 COMMENT '文档数',
                    chunk_count INT DEFAULT 0 COMMENT '分片数',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表'
                """);
        createTableIfMissing("knowledge_document", """
                CREATE TABLE knowledge_document (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识文档 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    title VARCHAR(255) DEFAULT NULL COMMENT '文档标题',
                    source_type VARCHAR(30) DEFAULT 'TEXT' COMMENT '来源类型',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    file_name VARCHAR(255) DEFAULT NULL COMMENT '文件名',
                    raw_text MEDIUMTEXT COMMENT '原始文本',
                    tags VARCHAR(500) DEFAULT NULL COMMENT '标签',
                    status VARCHAR(30) DEFAULT 'IMPORTED' COMMENT '状态',
                    char_count BIGINT DEFAULT 0 COMMENT '字符数',
                    error_message TEXT COMMENT '错误信息',
                    idempotency_key VARCHAR(128) DEFAULT NULL COMMENT '幂等键(executionId:nodeId)',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_document_base (knowledge_base_id),
                    INDEX idx_knowledge_document_idem (knowledge_base_id, idempotency_key),
                    INDEX idx_knowledge_document_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表'
                """);
        createTableIfMissing("knowledge_index_task", """
                CREATE TABLE knowledge_index_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '索引任务 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT NOT NULL COMMENT '文档 ID',
                    status VARCHAR(30) DEFAULT 'RUNNING' COMMENT '状态',
                    progress INT DEFAULT 0 COMMENT '进度',
                    total_chunks INT DEFAULT 0 COMMENT '总分片数',
                    finished_chunks INT DEFAULT 0 COMMENT '完成分片数',
                    error_message TEXT COMMENT '错误信息',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_index_task_base (knowledge_base_id),
                    INDEX idx_knowledge_index_task_document (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库索引任务表'
                """);
        createTableIfMissing("knowledge_chunk", """
                CREATE TABLE knowledge_chunk (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识片段主键 ID',
                    knowledge_base_id BIGINT NOT NULL COMMENT '知识库 ID',
                    document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID',
                    chunk_index INT DEFAULT 0 COMMENT '分片序号',
                    title VARCHAR(255) DEFAULT NULL COMMENT '标题',
                    content TEXT NOT NULL COMMENT '片段内容',
                    source_url VARCHAR(1024) DEFAULT NULL COMMENT '来源 URL',
                    tags JSON COMMENT '标签',
                    embedding_model VARCHAR(100) DEFAULT NULL COMMENT '向量模型',
                    embedding JSON COMMENT '向量数据',
                    status VARCHAR(30) DEFAULT 'READY' COMMENT '状态',
                    char_count INT DEFAULT 0 COMMENT '字符数',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_knowledge_base_id (knowledge_base_id),
                    INDEX idx_knowledge_document_id (document_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库片段表'
                """);
        createTableIfMissing("mcp_tool_config", """
                CREATE TABLE mcp_tool_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'MCP 工具配置 ID',
                    name VARCHAR(100) NOT NULL COMMENT '名称',
                    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
                    tool_type VARCHAR(50) DEFAULT 'custom' COMMENT '工具类型',
                    tool_name VARCHAR(100) NOT NULL COMMENT '暴露给 Agent 的工具名',
                    transport VARCHAR(30) DEFAULT 'stdio' COMMENT '传输方式',
                    command VARCHAR(500) NOT NULL COMMENT '启动命令',
                    args JSON COMMENT '启动参数',
                    env JSON COMMENT '环境变量',
                    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
                    preset TINYINT DEFAULT 0 COMMENT '是否预设',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                    INDEX idx_mcp_tool_name (tool_name),
                    INDEX idx_mcp_tool_type (tool_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP工具配置表'
                """);
        createTableIfMissing("execution_snapshot", """
                CREATE TABLE execution_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    flow_id BIGINT NOT NULL COMMENT '工作流 ID',
                    node_id VARCHAR(100) NOT NULL COMMENT '节点 ID',
                    node_type VARCHAR(50) NOT NULL COMMENT '节点类型',
                    node_name VARCHAR(255) DEFAULT NULL COMMENT '节点名称',
                    status VARCHAR(50) NOT NULL COMMENT '节点状态',
                    input_data JSON COMMENT '节点输入数据',
                    output_data JSON COMMENT '节点输出数据',
                    error_message TEXT COMMENT '错误信息',
                    started_at TIMESTAMP NULL COMMENT '开始执行时间',
                    completed_at TIMESTAMP NULL COMMENT '完成时间',
                    duration INT COMMENT '执行耗时(毫秒)',
                    retry_count INT DEFAULT 0 COMMENT '重试次数',
                    execution_order INT DEFAULT 0 COMMENT '执行顺序',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    INDEX idx_execution_id (execution_id),
                    INDEX idx_flow_id (flow_id),
                    INDEX idx_node_id (node_id),
                    INDEX idx_status (status),
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行快照表'
                """);
        createTableIfMissing("execution_variable", """
                CREATE TABLE execution_variable (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '变量主键 ID',
                    execution_id BIGINT NOT NULL COMMENT '执行记录 ID',
                    variable_name VARCHAR(100) NOT NULL COMMENT '变量名',
                    variable_type VARCHAR(50) DEFAULT 'STRING' COMMENT '变量类型',
                    variable_value TEXT COMMENT '变量值',
                    is_modified TINYINT DEFAULT 0 COMMENT '是否被修改',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    UNIQUE KEY uk_execution_variable (execution_id, variable_name),
                    INDEX idx_execution_id (execution_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行变量表'
                """);
        addColumnIfMissing("llm_global_config", "embedding_model",
                "ALTER TABLE llm_global_config ADD COLUMN embedding_model VARCHAR(100) DEFAULT NULL COMMENT '默认向量模型' AFTER tts_model");
        addColumnIfMissing("llm_global_config", "image_model",
                "ALTER TABLE llm_global_config ADD COLUMN image_model VARCHAR(100) DEFAULT NULL COMMENT '默认图片生成模型' AFTER embedding_model");
        addColumnIfMissing("llm_global_config", "video_model",
                "ALTER TABLE llm_global_config ADD COLUMN video_model VARCHAR(100) DEFAULT NULL COMMENT '默认视频生成模型' AFTER image_model");
        addColumnIfMissing("llm_global_config", "memory_enabled",
                "ALTER TABLE llm_global_config ADD COLUMN memory_enabled TINYINT DEFAULT 0 COMMENT '是否启用Agent Plan记忆能力' AFTER video_model");
        addColumnIfMissing("knowledge_document", "idempotency_key",
                "ALTER TABLE knowledge_document ADD COLUMN idempotency_key VARCHAR(128) DEFAULT NULL COMMENT '幂等键(executionId:nodeId)'");
        addIndexIfMissing("knowledge_document", "idx_knowledge_document_idem",
                "ALTER TABLE knowledge_document ADD INDEX idx_knowledge_document_idem (knowledge_base_id, idempotency_key)");
        addColumnIfMissing("execution_record", "updated_at",
                "ALTER TABLE execution_record ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP "
                        + "ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间(兼作执行心跳)'");
        addIndexIfMissing("execution_record", "idx_status_updated", "ALTER TABLE execution_record ADD INDEX idx_status_updated (status, updated_at)");
        // 节点耗时是毫秒级，起止时间只有秒精度会让时间线的起点最多偏差 1 秒
        widenToMillisIfNeeded("execution_snapshot", "started_at", "TIMESTAMP(3) NULL COMMENT '开始执行时间(毫秒精度)'");
        widenToMillisIfNeeded("execution_snapshot", "completed_at", "TIMESTAMP(3) NULL COMMENT '完成时间(毫秒精度)'");
        dedupeExecutionSnapshots();
        addIndexIfMissing("execution_snapshot", "uk_execution_node",
                "ALTER TABLE execution_snapshot ADD UNIQUE KEY uk_execution_node (execution_id, node_id)");
        addColumnIfMissing("knowledge_chunk", "document_id",
                "ALTER TABLE knowledge_chunk ADD COLUMN document_id BIGINT DEFAULT NULL COMMENT '知识文档 ID' AFTER knowledge_base_id");
        addColumnIfMissing("knowledge_chunk", "chunk_index",
                "ALTER TABLE knowledge_chunk ADD COLUMN chunk_index INT DEFAULT 0 COMMENT '分片序号' AFTER document_id");
        addColumnIfMissing("knowledge_chunk", "status",
                "ALTER TABLE knowledge_chunk ADD COLUMN status VARCHAR(30) DEFAULT 'READY' COMMENT '状态' AFTER embedding");
        addColumnIfMissing("knowledge_chunk", "char_count",
                "ALTER TABLE knowledge_chunk ADD COLUMN char_count INT DEFAULT 0 COMMENT '字符数' AFTER status");
        encryptExistingSecrets();
    }

    /**
     * 把历史明文 API Key 就地加密。用原生 JDBC 直接读写列（绕开 TypeHandler 才能看到库里的真实内容），
     * 已加密的值会被 encrypt 原样跳过，所以重复启动是幂等的；updated_at = updated_at 避免打乱列表排序。
     */
    private void encryptExistingSecrets() {
        int workflows = 0;
        for (var row : jdbcTemplate.queryForList("SELECT id, flow_data FROM workflow")) {
            String raw = (String) row.get("flow_data");
            String encrypted = secretCodec.encryptFlowData(raw);
            if (encrypted != null && !encrypted.equals(raw)) {
                jdbcTemplate.update("UPDATE workflow SET flow_data = ?, updated_at = updated_at WHERE id = ?",
                        encrypted, row.get("id"));
                workflows++;
            }
        }
        int configs = 0;
        for (var row : jdbcTemplate.queryForList("SELECT id, api_key FROM llm_global_config")) {
            String raw = (String) row.get("api_key");
            String encrypted = secretCodec.encrypt(raw);
            if (encrypted != null && !encrypted.equals(raw)) {
                jdbcTemplate.update("UPDATE llm_global_config SET api_key = ?, updated_at = updated_at WHERE id = ?",
                        encrypted, row.get("id"));
                configs++;
            }
        }
        int mcpTools = 0;
        for (var row : jdbcTemplate.queryForList("SELECT id, CAST(env AS CHAR) AS env FROM mcp_tool_config")) {
            String raw = (String) row.get("env");
            String encrypted = secretCodec.encryptEnv(raw);
            if (encrypted != null && !encrypted.equals(raw)) {
                jdbcTemplate.update("UPDATE mcp_tool_config SET env = ?, updated_at = updated_at WHERE id = ?",
                        encrypted, row.get("id"));
                mcpTools++;
            }
        }
        if (workflows + configs + mcpTools > 0) {
            log.warn("Schema migrated: encrypted plaintext secrets in {} workflow(s), {} global config(s), {} MCP tool(s)",
                    workflows, configs, mcpTools);
        }
    }

    private void createTableIfMissing(String tableName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, tableName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: created {}", tableName);
    }

    private void addIndexIfMissing(String tableName, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: added index {}.{}", tableName, indexName);
    }

    /** 加唯一约束前先清理历史重复快照，同一 (execution_id, node_id) 只保留最新一条。 */
    private void dedupeExecutionSnapshots() {
        Integer indexed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'execution_snapshot'
                  AND INDEX_NAME = 'uk_execution_node'
                """, Integer.class);
        if (indexed != null && indexed > 0) {
            return;
        }
        int removed = jdbcTemplate.update("""
                DELETE s1 FROM execution_snapshot s1
                JOIN execution_snapshot s2
                  ON s1.execution_id = s2.execution_id AND s1.node_id = s2.node_id AND s1.id < s2.id
                """);
        if (removed > 0) {
            log.warn("Schema migrated: removed {} duplicate execution_snapshot rows", removed);
        }
    }

    private void widenToMillisIfNeeded(String tableName, String columnName, String definition) {
        Integer precision = jdbcTemplate.queryForObject("""
                SELECT DATETIME_PRECISION
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (precision != null && precision >= 3) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition);
        log.info("Schema migrated: {}.{} widened to millisecond precision", tableName, columnName);
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("Schema migrated: added {}.{}", tableName, columnName);
    }
}
