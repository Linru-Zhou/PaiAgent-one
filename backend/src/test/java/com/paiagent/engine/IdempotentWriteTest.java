package com.paiagent.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.KnowledgeBaseRequest;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.KnowledgeDocument;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.KnowledgeBaseService;
import com.paiagent.support.FlowBuilder;
import com.paiagent.support.PaiAgentSpringTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 有副作用的写入节点：带幂等键重复执行（续跑/重试）不会产生重复数据。
 */
@PaiAgentSpringTest
class IdempotentWriteTest {

    private static final long FLOW_ID = 9_100_002L;

    @Autowired
    private AgentMemoryService memoryService;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private KnowledgeDocumentMapper documentMapper;
    @Autowired
    private WorkflowEngine engine;
    @Autowired
    private ExecutionRecordMapper recordMapper;
    @Autowired
    private ExecutionSnapshotMapper snapshotMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long knowledgeBaseId;

    @AfterEach
    void cleanup() {
        for (ExecutionRecord record : recordMapper.selectList(
                new LambdaQueryWrapper<ExecutionRecord>().eq(ExecutionRecord::getFlowId, FLOW_ID))) {
            snapshotMapper.deleteByExecutionId(record.getId());
            recordMapper.deleteById(record.getId());
        }
        if (knowledgeBaseId != null) {
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE knowledge_base_id = ?", knowledgeBaseId);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE knowledge_base_id = ?", knowledgeBaseId);
            jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
        }
    }

    private int memoryRows(String content) throws Exception {
        File db = new File("target/test-data/agent-memory-test.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM agent_memory WHERE content = '" + content + "'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Test
    void memoryWrite_withoutKey_appendsDuplicates_withKey_replaces() throws Exception {
        String content = "memo-" + UUID.randomUUID();

        memoryService.write(content, "fact", "workflow", List.of(), "n1", List.of(), "m");
        memoryService.write(content, "fact", "workflow", List.of(), "n1", List.of(), "m");
        assertEquals(2, memoryRows(content), "没有幂等键：重复写入会追加重复记忆（修复前的行为）");

        String keyed = "memo-keyed-" + UUID.randomUUID();
        memoryService.write(keyed, "fact", "workflow", List.of(), "n1", List.of(), "m", "7:n1");
        Map<String, Object> second = memoryService.write(keyed, "fact", "workflow", List.of(), "n1", List.of(), "m", "7:n1");
        assertEquals(1, memoryRows(keyed), "带幂等键：重复写入只覆盖同一条");
        assertEquals(true, second.get("replaced"));
    }

    @Test
    void memoryWriteNode_rerunByResume_doesNotDuplicate() throws Exception {
        String content = "node-memo-" + UUID.randomUUID();
        Workflow workflow = new FlowBuilder()
                .input("in")
                .node("mem", "memory_write", Map.of("content", content))
                .output("out")
                .edge("in", "mem").edge("mem", "out")
                .build(FLOW_ID, "dag");

        ExecutionResponse first = engine.execute(workflow, "hello");
        assertEquals("SUCCESS", first.getStatus());
        assertEquals(1, memoryRows(content));

        ResumeExecutionRequest rerunFromMemoryNode = new ResumeExecutionRequest();
        rerunFromMemoryNode.setStartNodeId("mem");
        rerunFromMemoryNode.setSkipSuccessNodes(true);
        ExecutionResponse rerun = engine.resumeExecution(workflow, first.getExecutionId(), rerunFromMemoryNode, null);

        assertEquals("SUCCESS", rerun.getStatus());
        assertEquals(1, memoryRows(content), "从写记忆节点重新执行，不应再多出一条重复记忆");
    }

    @Test
    void knowledgeUpsert_withKey_updatesSameDocument() {
        KnowledgeBaseRequest request = new KnowledgeBaseRequest();
        request.setName("idem-test-" + UUID.randomUUID());
        Map<String, Object> base = knowledgeBaseService.createKnowledgeBase(request);
        knowledgeBaseId = ((Number) base.get("id")).longValue();

        knowledgeBaseService.upsert(String.valueOf(knowledgeBaseId), "t", "第一版内容", null, List.of(), List.of(), "m");
        knowledgeBaseService.upsert(String.valueOf(knowledgeBaseId), "t", "第一版内容", null, List.of(), List.of(), "m");
        assertEquals(2L, documentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)), "没有幂等键：每次都新建文档（修复前的行为）");

        knowledgeBaseService.upsert(String.valueOf(knowledgeBaseId), "t", "带键内容 v1", null, List.of(), List.of(), "m", "9:kn");
        knowledgeBaseService.upsert(String.valueOf(knowledgeBaseId), "t", "带键内容 v2", null, List.of(), List.of(), "m", "9:kn");
        assertEquals(1L, documentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getIdempotencyKey, "9:kn")), "带幂等键：同一个键只对应一篇文档");
        KnowledgeDocument doc = documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getIdempotencyKey, "9:kn"));
        assertEquals("带键内容 v2", doc.getRawText(), "重复写入后内容应更新为最新版本");
    }
}
