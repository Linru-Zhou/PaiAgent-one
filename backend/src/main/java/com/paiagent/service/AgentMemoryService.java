package com.paiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 长期记忆存储与检索。
 * 持久化到独立的 SQLite 文件（与主库 MySQL 解耦，记忆读写量小、访问模式偏本地化，
 * 用嵌入式 SQLite 更轻量，也方便单机/边缘部署）。
 * 检索采用关键词（SQLite FTS5 + bm25）与向量（余弦相似度）双路召回，
 * 用 RRF（Reciprocal Rank Fusion）融合排序，而不是"有向量就用向量，没有就退化成关键词"的单路降级。
 */
@Service
public class AgentMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(AgentMemoryService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int RRF_K = 60;

    @Value("${paiagent.memory.sqlite-path:data/agent-memory.db}")
    private String sqlitePath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String jdbcUrl;

    @PostConstruct
    public void init() {
        File dbFile = new File(sqlitePath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_memory (
                        id TEXT PRIMARY KEY,
                        scope TEXT NOT NULL,
                        memory_type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tags TEXT,
                        source TEXT,
                        embedding TEXT,
                        embedding_model TEXT,
                        created_at TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS agent_memory_fts USING fts5(
                        memory_id UNINDEXED,
                        content
                    )
                    """);
            logger.info("Agent 长期记忆 SQLite 存储已就绪: {}", dbFile.getAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 Agent 长期记忆 SQLite 存储失败", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public Map<String, Object> write(String content, String memoryType, String scope,
                                     List<String> tags, String source, List<Double> embedding,
                                     String embeddingModel) {
        return write(content, memoryType, scope, tags, source, embedding, embeddingModel, null);
    }

    /**
     * 写入长期记忆。传入 idempotencyKey（通常是 executionId:nodeId）时，记忆 ID 由 key 确定性生成，
     * 同一个 key 重复写入是"覆盖同一条"而不是再追加一条，工作流断点续跑/重试因此不会产生重复记忆。
     */
    public Map<String, Object> write(String content, String memoryType, String scope,
                                     List<String> tags, String source, List<Double> embedding,
                                     String embeddingModel, String idempotencyKey) {
        boolean idempotent = StringUtils.hasText(idempotencyKey);
        String id = idempotent
                ? "idem-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : UUID.randomUUID().toString();
        boolean replaced = false;
        String resolvedScope = StringUtils.hasText(scope) ? scope : "workflow";
        String resolvedType = StringUtils.hasText(memoryType) ? memoryType : "fact";
        String tagsJson = writeJson(tags == null ? List.of() : tags);
        String embeddingJson = writeJson(embedding == null ? List.of() : embedding);
        String createdAt = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            if (idempotent) {
                try (PreparedStatement deleteOld = conn.prepareStatement("DELETE FROM agent_memory WHERE id = ?");
                     PreparedStatement deleteOldFts = conn.prepareStatement("DELETE FROM agent_memory_fts WHERE memory_id = ?")) {
                    deleteOld.setString(1, id);
                    replaced = deleteOld.executeUpdate() > 0;
                    deleteOldFts.setString(1, id);
                    deleteOldFts.executeUpdate();
                }
            }
            try (PreparedStatement insertMemory = conn.prepareStatement(
                    "INSERT INTO agent_memory (id, scope, memory_type, content, tags, source, embedding, embedding_model, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insertMemory.setString(1, id);
                insertMemory.setString(2, resolvedScope);
                insertMemory.setString(3, resolvedType);
                insertMemory.setString(4, content);
                insertMemory.setString(5, tagsJson);
                insertMemory.setString(6, source);
                insertMemory.setString(7, embeddingJson);
                insertMemory.setString(8, embeddingModel);
                insertMemory.setString(9, createdAt);
                insertMemory.executeUpdate();
            }
            try (PreparedStatement insertFts = conn.prepareStatement(
                    "INSERT INTO agent_memory_fts (memory_id, content) VALUES (?, ?)")) {
                insertFts.setString(1, id);
                insertFts.setString(2, content);
                insertFts.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 Agent 长期记忆失败", e);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("memoryId", id);
        output.put("scope", resolvedScope);
        output.put("stored", true);
        if (idempotent) {
            output.put("replaced", replaced);
        }
        return output;
    }

    public Map<String, Object> retrieve(String query, String scope, List<String> tags,
                                        List<Double> queryEmbedding, int topK) {
        List<MemoryRow> candidates = loadCandidates(scope, tags);

        // 关键词召回：SQLite FTS5 + 内置 bm25() 排序，取候选集内命中的排名
        List<String> keywordRankedIds = keywordRank(query, candidates);

        // 向量召回：候选集内做余弦相似度排序
        List<String> vectorRankedIds = vectorRank(queryEmbedding, candidates);

        // RRF 融合两路排名，而不是简单加权分数相加（避免两路分数量纲不一致的问题）
        Map<String, Double> fused = reciprocalRankFusion(keywordRankedIds, vectorRankedIds);

        Map<String, MemoryRow> byId = new LinkedHashMap<>();
        for (MemoryRow row : candidates) {
            byId.put(row.id, row);
        }

        List<Map<String, Object>> matches = fused.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(Math.max(1, topK))
                .map(e -> toMatch(byId.get(e.getKey()), e.getValue()))
                .toList();

        StringBuilder context = new StringBuilder();
        for (Map<String, Object> match : matches) {
            context.append("- ").append(match.get("content")).append("\n");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("memories", matches);
        output.put("context", context.toString().trim());
        output.put("citations", matches.stream().map(match -> match.get("memoryId")).toList());
        return output;
    }

    private List<MemoryRow> loadCandidates(String scope, List<String> tags) {
        List<MemoryRow> rows = new ArrayList<>();
        String sql = "SELECT id, scope, memory_type, content, tags, source, embedding FROM agent_memory" +
                (StringUtils.hasText(scope) ? " WHERE scope = ?" : "");
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (StringUtils.hasText(scope)) {
                ps.setString(1, scope);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> rowTags = readJsonList(rs.getString("tags"));
                    if (tags != null && !tags.isEmpty() && !rowTags.containsAll(tags)) {
                        continue;
                    }
                    MemoryRow row = new MemoryRow();
                    row.id = rs.getString("id");
                    row.scope = rs.getString("scope");
                    row.memoryType = rs.getString("memory_type");
                    row.content = rs.getString("content");
                    row.tags = rowTags;
                    row.source = rs.getString("source");
                    row.embedding = readJsonDoubleList(rs.getString("embedding"));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Agent 长期记忆失败", e);
        }
        return rows;
    }

    private List<String> keywordRank(String query, List<MemoryRow> candidates) {
        if (!StringUtils.hasText(query) || candidates.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> candidateIds = new java.util.HashSet<>();
        for (MemoryRow row : candidates) {
            candidateIds.add(row.id);
        }
        String matchQuery = buildFtsMatchQuery(query);
        if (matchQuery.isEmpty()) {
            return List.of();
        }
        List<String> ranked = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT memory_id FROM agent_memory_fts WHERE agent_memory_fts MATCH ? ORDER BY bm25(agent_memory_fts) ASC LIMIT 200")) {
            ps.setString(1, matchQuery);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("memory_id");
                    if (candidateIds.contains(id)) {
                        ranked.add(id);
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("FTS5 关键词检索失败，跳过关键词召回路: {}", e.getMessage());
            return List.of();
        }
        return ranked;
    }

    private String buildFtsMatchQuery(String query) {
        String[] tokens = query.trim().split("[\\s,，。.!?！？]+");
        List<String> quoted = new ArrayList<>();
        for (String token : tokens) {
            if (StringUtils.hasText(token)) {
                quoted.add("\"" + token.replace("\"", "") + "\"");
            }
        }
        return String.join(" OR ", quoted);
    }

    /**
     * cosine > 0 几乎总是成立，不是有效的相关性过滤。实测本地 embedding 模型（nomic-embed-text）
     * 对完全不相关的短句也能给到 0.55~0.70 的 cosine，真正的同义改写能到 0.89+；0.75 是照实测
     * 数据定的下限，不是随便拍的数字。换了 embedding 模型要重新用真实数据校准这个值。
     */
    private static final double MIN_COSINE_SIMILARITY = 0.75;

    private List<String> vectorRank(List<Double> queryEmbedding, List<MemoryRow> candidates) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(row -> row.embedding != null && !row.embedding.isEmpty())
                .map(row -> Map.entry(row.id, cosine(queryEmbedding, row.embedding)))
                .filter(e -> e.getValue() >= MIN_COSINE_SIMILARITY)
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Double> reciprocalRankFusion(List<String> keywordRankedIds, List<String> vectorRankedIds) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < keywordRankedIds.size(); i++) {
            String id = keywordRankedIds.get(i);
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vectorRankedIds.size(); i++) {
            String id = vectorRankedIds.get(i);
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        return scores;
    }

    private Map<String, Object> toMatch(MemoryRow row, double score) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("memoryId", row.id);
        match.put("scope", row.scope);
        match.put("memoryType", row.memoryType);
        match.put("content", row.content);
        match.put("tags", row.tags);
        match.put("source", row.source);
        match.put("score", score);
        return match;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public List<String> splitTags(String tagText) {
        if (!StringUtils.hasText(tagText)) {
            return new ArrayList<>();
        }
        String[] parts = tagText.split("[,，\\s]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            logger.warn("序列化记忆字段失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Double> readJsonDoubleList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static class MemoryRow {
        String id;
        String scope;
        String memoryType;
        String content;
        List<String> tags;
        String source;
        List<Double> embedding;
    }
}
