package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.KnowledgePreviewRequest;
import com.paiagent.dto.KnowledgeSearchRequest;
import com.paiagent.dto.KnowledgeTextImportRequest;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeDocument;
import com.paiagent.entity.KnowledgeIndexTask;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeIndexTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeBaseService {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeIndexTaskMapper knowledgeIndexTaskMapper;
    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                KnowledgeDocumentMapper knowledgeDocumentMapper,
                                KnowledgeChunkMapper knowledgeChunkMapper,
                                KnowledgeIndexTaskMapper knowledgeIndexTaskMapper,
                                AgentPlanConfigResolver configResolver,
                                VolcengineAgentPlanClient agentPlanClient) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeIndexTaskMapper = knowledgeIndexTaskMapper;
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
    }

    public List<Map<String, Object>> listKnowledgeBases() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .orderByDesc(KnowledgeBase::getUpdatedAt))
                .stream()
                .map(this::toBaseMap)
                .toList();
    }

    public Map<String, Object> getKnowledgeBase(Long id) {
        KnowledgeBase base = requireBase(id);
        Map<String, Object> output = toBaseMap(base);
        output.put("documents", listDocuments(id));
        output.put("recentTasks", knowledgeIndexTaskMapper.selectList(new LambdaQueryWrapper<KnowledgeIndexTask>()
                .eq(KnowledgeIndexTask::getKnowledgeBaseId, id)
                .orderByDesc(KnowledgeIndexTask::getUpdatedAt)
                .last("LIMIT 10")).stream().map(this::toTaskMap).toList());
        return output;
    }

    @Transactional
    public void deleteKnowledgeBase(Long id) {
        requireBase(id);
        knowledgeChunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        knowledgeIndexTaskMapper.delete(new LambdaQueryWrapper<KnowledgeIndexTask>()
                .eq(KnowledgeIndexTask::getKnowledgeBaseId, id));
        knowledgeDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, id));
        knowledgeBaseMapper.deleteById(id);
    }

    @Transactional
    public Map<String, Object> createKnowledgeBase(KnowledgeBaseRequest request) {
        KnowledgeBase base = new KnowledgeBase();
        base.setName(request.getName().trim());
        base.setDescription(trimToNull(request.getDescription()));
        base.setConfigId(request.getConfigId());
        base.setEmbeddingModel(firstText(request.getEmbeddingModel(), AgentPlanConfigResolver.DEFAULT_EMBEDDING_MODEL));
        base.setChunkSize(normalizeChunkSize(request.getChunkSize()));
        base.setChunkOverlap(normalizeChunkOverlap(request.getChunkOverlap(), base.getChunkSize()));
        base.setStatus("DRAFT");
        base.setDocumentCount(0);
        base.setChunkCount(0);
        base.setCharCount(0L);
        knowledgeBaseMapper.insert(base);
        return toBaseMap(base);
    }

    @Transactional
    public Map<String, Object> importText(Long knowledgeBaseId, KnowledgeTextImportRequest request) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(base.getId());
        document.setTitle(firstText(request.getTitle(), "未命名文本"));
        document.setSourceType("TEXT");
        document.setRawText(request.getContent());
        document.setTags(trimToNull(request.getTags()));
        document.setStatus("IMPORTED");
        document.setCharCount((long) request.getContent().length());
        knowledgeDocumentMapper.insert(document);
        refreshBaseStats(base.getId(), "IMPORTED");
        return toDocumentMap(document);
    }

    @Transactional
    public Map<String, Object> uploadTextFile(Long knowledgeBaseId, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = firstText(file.getOriginalFilename(), "uploaded.txt");
        if (!isTextFile(fileName)) {
            throw new IllegalArgumentException("当前版本仅支持 txt、md、markdown 文本文件");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        KnowledgeTextImportRequest request = new KnowledgeTextImportRequest();
        request.setTitle(fileName);
        request.setContent(content);
        Map<String, Object> output = importText(knowledgeBaseId, request);
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(((Number) output.get("id")).longValue());
        document.setFileName(fileName);
        knowledgeDocumentMapper.updateById(document);
        return toDocumentMap(document);
    }

    public List<Map<String, Object>> listDocuments(Long knowledgeBaseId) {
        return knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(KnowledgeDocument::getUpdatedAt))
                .stream()
                .map(this::toDocumentMap)
                .toList();
    }

    public List<Map<String, Object>> previewChunks(Long knowledgeBaseId, Long documentId, KnowledgePreviewRequest request) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        KnowledgeDocument document = requireDocument(knowledgeBaseId, documentId);
        int chunkSize = normalizeChunkSize(request == null ? null : request.getChunkSize(), base.getChunkSize());
        int overlap = normalizeChunkOverlap(request == null ? null : request.getChunkOverlap(), chunkSize, base.getChunkOverlap());
        List<String> chunks = splitContent(document.getRawText(), chunkSize, overlap);
        List<Map<String, Object>> output = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkIndex", i);
            item.put("content", chunks.get(i));
            item.put("charCount", chunks.get(i).length());
            output.add(item);
        }
        return output;
    }

    @Transactional
    public Map<String, Object> indexDocument(Long knowledgeBaseId, Long documentId) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        KnowledgeDocument document = requireDocument(knowledgeBaseId, documentId);
        KnowledgeIndexTask task = createTask(base.getId(), document.getId());
        try {
            List<String> chunks = splitContent(document.getRawText(), base.getChunkSize(), base.getChunkOverlap());
            markOldChunksDeleted(base.getId(), document.getId());

            ResolvedAgentPlanConfig config = configResolver.resolveKnowledgeConfig(base.getConfigId(), base.getEmbeddingModel());
            boolean canEmbed = StringUtils.hasText(config.apiUrl()) && StringUtils.hasText(config.apiKey()) && StringUtils.hasText(config.model());
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                List<Double> embedding = canEmbed ? agentPlanClient.createEmbedding(config, chunkText) : List.of();
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setKnowledgeBaseId(base.getId());
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(i);
                chunk.setTitle(document.getTitle());
                chunk.setContent(chunkText);
                chunk.setSourceUrl(document.getSourceUrl());
                chunk.setTags(toTagsJson(document.getTags()));
                chunk.setEmbeddingModel(config.model());
                chunk.setEmbedding(JSON.toJSONString(embedding));
                chunk.setStatus("READY");
                chunk.setCharCount(chunkText.length());
                knowledgeChunkMapper.insert(chunk);

                task.setFinishedChunks(i + 1);
                task.setTotalChunks(chunks.size());
                task.setProgress(chunks.isEmpty() ? 100 : (int) Math.round(((i + 1) * 100.0) / chunks.size()));
                knowledgeIndexTaskMapper.updateById(task);
            }

            document.setStatus("READY");
            document.setErrorMessage(null);
            knowledgeDocumentMapper.updateById(document);
            task.setStatus("SUCCESS");
            task.setProgress(100);
            knowledgeIndexTaskMapper.updateById(task);
            refreshBaseStats(base.getId(), "READY");
        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            knowledgeDocumentMapper.updateById(document);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            knowledgeIndexTaskMapper.updateById(task);
            refreshBaseStats(base.getId(), "FAILED");
        }
        return toTaskMap(task);
    }

    public Map<String, Object> search(Long knowledgeBaseId, KnowledgeSearchRequest request) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        List<Double> queryEmbedding = List.of();
        try {
            ResolvedAgentPlanConfig config = configResolver.resolveKnowledgeConfig(base.getConfigId(), base.getEmbeddingModel());
            if (StringUtils.hasText(config.apiUrl()) && StringUtils.hasText(config.apiKey()) && StringUtils.hasText(config.model())) {
                queryEmbedding = agentPlanClient.createEmbedding(config, request.getQuery());
            }
        } catch (Exception ignored) {
            queryEmbedding = List.of();
        }
        return retrieve(String.valueOf(base.getId()), request.getQuery(), queryEmbedding,
                request.getTopK() == null ? 5 : request.getTopK(),
                request.getScoreThreshold() == null ? 0.2 : request.getScoreThreshold());
    }

    public Map<String, Object> searchRuntime(String knowledgeBaseId, String query, int topK, double scoreThreshold) {
        Long baseId = resolveBaseId(knowledgeBaseId);
        KnowledgeBase base = requireBase(baseId);
        List<Double> queryEmbedding = List.of();
        try {
            ResolvedAgentPlanConfig config = configResolver.resolveKnowledgeConfig(base.getConfigId(), base.getEmbeddingModel());
            if (StringUtils.hasText(config.apiUrl()) && StringUtils.hasText(config.apiKey()) && StringUtils.hasText(config.model())) {
                queryEmbedding = agentPlanClient.createEmbedding(config, query);
            }
        } catch (Exception ignored) {
            queryEmbedding = List.of();
        }
        return retrieve(String.valueOf(base.getId()), query, queryEmbedding, topK, scoreThreshold);
    }

    public Map<String, Object> upsert(String knowledgeBaseId, String title, String content,
                                      String sourceUrl, List<String> tags, List<Double> embedding,
                                      String embeddingModel) {
        return upsert(knowledgeBaseId, title, content, sourceUrl, tags, embedding, embeddingModel, null);
    }

    /**
     * 写入知识库。传入 idempotencyKey（通常是 executionId:nodeId）时，同一个 key 重复写入会覆盖同一篇文档的内容与分片，
     * 而不是每次都新建一篇文档，工作流断点续跑/重试因此不会让知识库里出现重复内容。
     * （同一次执行同一时刻只会有一个请求在跑，由执行状态的乐观锁保证，所以这里用"先查再写"即可。）
     */
    public Map<String, Object> upsert(String knowledgeBaseId, String title, String content,
                                      String sourceUrl, List<String> tags, List<Double> embedding,
                                      String embeddingModel, String idempotencyKey) {
        KnowledgeBase base = resolveBaseForRuntime(knowledgeBaseId, embeddingModel);
        KnowledgeDocument document = null;
        if (StringUtils.hasText(idempotencyKey)) {
            document = knowledgeDocumentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getKnowledgeBaseId, base.getId())
                    .eq(KnowledgeDocument::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
        }
        if (document != null) {
            document.setTitle(firstText(title, "工作流写入"));
            document.setRawText(content);
            document.setTags(tags == null || tags.isEmpty() ? null : String.join(",", tags));
            document.setCharCount((long) content.length());
            knowledgeDocumentMapper.updateById(document);
        } else {
            KnowledgeTextImportRequest request = new KnowledgeTextImportRequest();
            request.setTitle(firstText(title, "工作流写入"));
            request.setContent(content);
            request.setTags(tags == null ? null : String.join(",", tags));
            Map<String, Object> documentMap = importText(base.getId(), request);
            document = knowledgeDocumentMapper.selectById(((Number) documentMap.get("id")).longValue());
            if (StringUtils.hasText(idempotencyKey)) {
                document.setIdempotencyKey(idempotencyKey);
                knowledgeDocumentMapper.updateById(document);
            }
        }
        List<String> chunks = splitContent(content, base.getChunkSize(), base.getChunkOverlap());
        markOldChunksDeleted(base.getId(), document.getId());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setKnowledgeBaseId(base.getId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setTitle(document.getTitle());
            chunk.setContent(chunks.get(i));
            chunk.setSourceUrl(sourceUrl);
            chunk.setTags(toTagsJson(document.getTags()));
            chunk.setEmbeddingModel(embeddingModel);
            chunk.setEmbedding(JSON.toJSONString(i == 0 && embedding != null ? embedding : List.of()));
            chunk.setStatus("READY");
            chunk.setCharCount(chunks.get(i).length());
            knowledgeChunkMapper.insert(chunk);
        }
        document.setStatus("READY");
        document.setSourceUrl(sourceUrl);
        knowledgeDocumentMapper.updateById(document);
        refreshBaseStats(base.getId(), "READY");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("knowledgeBaseId", base.getId());
        output.put("contentId", document.getId());
        output.put("chunkCount", chunks.size());
        output.put("indexed", true);
        return output;
    }

    public Map<String, Object> retrieve(String knowledgeBaseId, String query, List<Double> queryEmbedding,
                                        int topK, double scoreThreshold) {
        Long baseId = resolveBaseId(knowledgeBaseId);
        double effectiveThreshold = scoreThreshold <= 0 ? 0.000001 : scoreThreshold;
        List<Map<String, Object>> matches = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, baseId)
                        .eq(KnowledgeChunk::getStatus, "READY"))
                .stream()
                .map(chunk -> toMatch(chunk, query, queryEmbedding))
                .filter(match -> (double) match.get("score") >= effectiveThreshold)
                .sorted(Comparator.comparingDouble((Map<String, Object> match) -> (double) match.get("score")).reversed())
                .limit(Math.max(1, topK))
                .toList();

        StringBuilder context = new StringBuilder();
        for (Map<String, Object> match : matches) {
            context.append("- ").append(match.get("content")).append("\n");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunks", matches);
        output.put("citations", matches.stream().map(match -> match.get("chunkId")).toList());
        output.put("context", context.toString().trim());
        return output;
    }

    private KnowledgeIndexTask createTask(Long baseId, Long documentId) {
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setKnowledgeBaseId(baseId);
        task.setDocumentId(documentId);
        task.setStatus("RUNNING");
        task.setProgress(0);
        task.setTotalChunks(0);
        task.setFinishedChunks(0);
        knowledgeIndexTaskMapper.insert(task);
        return task;
    }

    private KnowledgeBase resolveBaseForRuntime(String knowledgeBaseId, String embeddingModel) {
        if (StringUtils.hasText(knowledgeBaseId) && !"default".equalsIgnoreCase(knowledgeBaseId.trim())) {
            try {
                return requireBase(Long.parseLong(knowledgeBaseId.trim()));
            } catch (NumberFormatException ignored) {
                KnowledgeBase named = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getName, knowledgeBaseId.trim())
                        .last("LIMIT 1"));
                if (named != null) {
                    return named;
                }
            }
        }
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getName, "默认知识库")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        KnowledgeBaseRequest request = new KnowledgeBaseRequest();
        request.setName("默认知识库");
        request.setDescription("工作流运行时自动写入的默认知识库");
        request.setEmbeddingModel(embeddingModel);
        Map<String, Object> created = createKnowledgeBase(request);
        return requireBase(((Number) created.get("id")).longValue());
    }

    private Long resolveBaseId(String knowledgeBaseId) {
        KnowledgeBase base = resolveBaseForRuntime(knowledgeBaseId, null);
        return base.getId();
    }

    private KnowledgeBase requireBase(Long id) {
        KnowledgeBase base = knowledgeBaseMapper.selectById(id);
        if (base == null) {
            throw new IllegalArgumentException("知识库不存在");
        }
        return base;
    }

    private KnowledgeDocument requireDocument(Long knowledgeBaseId, Long documentId) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null || !knowledgeBaseId.equals(document.getKnowledgeBaseId())) {
            throw new IllegalArgumentException("知识文档不存在");
        }
        return document;
    }

    private void markOldChunksDeleted(Long baseId, Long documentId) {
        knowledgeChunkMapper.update(null, new LambdaUpdateWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, baseId)
                .eq(KnowledgeChunk::getDocumentId, documentId)
                .set(KnowledgeChunk::getDeleted, 1));
    }

    private void refreshBaseStats(Long baseId, String status) {
        KnowledgeBase base = requireBase(baseId);
        Long documentCount = knowledgeDocumentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId));
        Long chunkCount = knowledgeChunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, baseId)
                .eq(KnowledgeChunk::getStatus, "READY"));
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId));
        long charCount = documents.stream()
                .map(KnowledgeDocument::getCharCount)
                .filter(count -> count != null)
                .mapToLong(Long::longValue)
                .sum();
        base.setDocumentCount(documentCount.intValue());
        base.setChunkCount(chunkCount.intValue());
        base.setCharCount(charCount);
        base.setStatus(status);
        knowledgeBaseMapper.updateById(base);
    }

    private List<String> splitContent(String content, Integer chunkSize, Integer overlap) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        int size = normalizeChunkSize(chunkSize);
        int safeOverlap = normalizeChunkOverlap(overlap, size);
        int step = Math.max(1, size - safeOverlap);
        String text = content.trim();
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + size);
            result.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> toMatch(KnowledgeChunk chunk, String query, List<Double> queryEmbedding) {
        List<Double> chunkEmbedding = parseEmbedding(chunk.getEmbedding());
        double vectorScore = cosine(queryEmbedding, chunkEmbedding);
        double textScore = textRelevance(query, chunk.getContent());
        double score = Math.max(vectorScore, textScore);
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("chunkId", chunk.getId());
        match.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
        match.put("documentId", chunk.getDocumentId());
        match.put("title", chunk.getTitle());
        match.put("content", chunk.getContent());
        match.put("sourceUrl", chunk.getSourceUrl());
        match.put("tags", chunk.getTags());
        match.put("score", score);
        return match;
    }

    private double textRelevance(String query, String content) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(content)) {
            return 0;
        }
        String normalizedQuery = normalizeSearchText(query);
        String normalizedContent = normalizeSearchText(content);
        if (!StringUtils.hasText(normalizedQuery) || !StringUtils.hasText(normalizedContent)) {
            return 0;
        }
        if (normalizedContent.contains(normalizedQuery)) {
            return 1;
        }

        Set<String> terms = searchTerms(normalizedQuery);
        if (terms.isEmpty()) {
            return 0;
        }

        double totalWeight = 0;
        double matchedWeight = 0;
        for (String term : terms) {
            double weight = Math.min(4, Math.max(1, term.length() - 1));
            totalWeight += weight;
            if (normalizedContent.contains(term)) {
                matchedWeight += weight;
            }
        }
        if (totalWeight == 0 || matchedWeight == 0) {
            return 0;
        }
        return Math.min(0.95, matchedWeight / totalWeight);
    }

    private String normalizeSearchText(String text) {
        return text == null ? "" : text.toLowerCase()
                .replaceAll("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> searchTerms(String normalizedQuery) {
        Set<String> terms = new LinkedHashSet<>();
        for (String part : normalizedQuery.split("\\s+")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (containsHan(part)) {
                addChineseTerms(terms, part);
            } else if (part.length() >= 2 && !isStopTerm(part)) {
                terms.add(part);
            }
        }
        return terms;
    }

    private void addChineseTerms(Set<String> terms, String text) {
        if (text.length() <= 4 && !isStopTerm(text)) {
            terms.add(text);
            return;
        }
        for (int size = 2; size <= 4; size++) {
            if (text.length() < size) {
                continue;
            }
            for (int i = 0; i <= text.length() - size; i++) {
                String term = text.substring(i, i + size);
                if (!isStopTerm(term)) {
                    terms.add(term);
                }
            }
        }
    }

    private boolean containsHan(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isStopTerm(String term) {
        return switch (term) {
            case "应该", "怎么", "什么", "如何", "可以", "需要", "一个", "这个", "那个", "是否", "以及",
                 "the", "and", "for", "with", "what", "how" -> true;
            default -> false;
        };
    }

    private Map<String, Object> toBaseMap(KnowledgeBase base) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", base.getId());
        output.put("name", base.getName());
        output.put("description", base.getDescription());
        output.put("configId", base.getConfigId());
        output.put("embeddingModel", base.getEmbeddingModel());
        output.put("chunkSize", base.getChunkSize());
        output.put("chunkOverlap", base.getChunkOverlap());
        output.put("status", base.getStatus());
        output.put("documentCount", base.getDocumentCount());
        output.put("chunkCount", base.getChunkCount());
        output.put("charCount", base.getCharCount());
        output.put("createdAt", base.getCreatedAt());
        output.put("updatedAt", base.getUpdatedAt());
        return output;
    }

    private Map<String, Object> toDocumentMap(KnowledgeDocument document) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", document.getId());
        output.put("knowledgeBaseId", document.getKnowledgeBaseId());
        output.put("title", document.getTitle());
        output.put("sourceType", document.getSourceType());
        output.put("sourceUrl", document.getSourceUrl());
        output.put("fileName", document.getFileName());
        output.put("tags", document.getTags());
        output.put("status", document.getStatus());
        output.put("charCount", document.getCharCount());
        output.put("errorMessage", document.getErrorMessage());
        output.put("createdAt", document.getCreatedAt());
        output.put("updatedAt", document.getUpdatedAt());
        return output;
    }

    private Map<String, Object> toTaskMap(KnowledgeIndexTask task) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", task.getId());
        output.put("knowledgeBaseId", task.getKnowledgeBaseId());
        output.put("documentId", task.getDocumentId());
        output.put("status", task.getStatus());
        output.put("progress", task.getProgress());
        output.put("totalChunks", task.getTotalChunks());
        output.put("finishedChunks", task.getFinishedChunks());
        output.put("errorMessage", task.getErrorMessage());
        output.put("createdAt", task.getCreatedAt());
        output.put("updatedAt", task.getUpdatedAt());
        return output;
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (!StringUtils.hasText(embeddingJson)) {
            return List.of();
        }
        try {
            return JSON.parseArray(embeddingJson, Double.class);
        } catch (Exception ignored) {
            return List.of();
        }
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

    private boolean isTextFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private int normalizeChunkSize(Integer chunkSize) {
        return normalizeChunkSize(chunkSize, DEFAULT_CHUNK_SIZE);
    }

    private int normalizeChunkSize(Integer chunkSize, Integer fallback) {
        int value = chunkSize == null ? (fallback == null ? DEFAULT_CHUNK_SIZE : fallback) : chunkSize;
        return Math.max(100, Math.min(4000, value));
    }

    private int normalizeChunkOverlap(Integer overlap, Integer chunkSize) {
        return normalizeChunkOverlap(overlap, chunkSize, DEFAULT_CHUNK_OVERLAP);
    }

    private int normalizeChunkOverlap(Integer overlap, Integer chunkSize, Integer fallback) {
        int value = overlap == null ? (fallback == null ? DEFAULT_CHUNK_OVERLAP : fallback) : overlap;
        return Math.max(0, Math.min(Math.max(0, chunkSize - 1), value));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String toTagsJson(String tagsText) {
        if (!StringUtils.hasText(tagsText)) {
            return JSON.toJSONString(List.of());
        }
        List<String> tags = new ArrayList<>();
        for (String item : tagsText.split("[,，\\s]+")) {
            if (StringUtils.hasText(item)) {
                tags.add(item.trim());
            }
        }
        return JSON.toJSONString(tags);
    }
}
