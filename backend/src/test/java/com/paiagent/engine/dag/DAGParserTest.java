package com.paiagent.engine.dag;

import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DAGParserTest {

    private final DAGParser parser = new DAGParser();

    private static WorkflowNode node(String id) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType("test_task");
        return node;
    }

    private static WorkflowEdge edge(String source, String target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(source + "-" + target);
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
    }

    private static WorkflowConfig config(List<String> nodeIds, String... edgePairs) {
        WorkflowConfig config = new WorkflowConfig();
        List<WorkflowNode> nodes = new ArrayList<>();
        nodeIds.forEach(id -> nodes.add(node(id)));
        List<WorkflowEdge> edges = new ArrayList<>();
        for (String pair : edgePairs) {
            String[] parts = pair.split(">");
            edges.add(edge(parts[0], parts[1]));
        }
        config.setNodes(nodes);
        config.setEdges(edges);
        return config;
    }

    private static List<String> ids(List<WorkflowNode> nodes) {
        return nodes.stream().map(WorkflowNode::getId).collect(Collectors.toList());
    }

    private static void assertRespectsDependencies(List<WorkflowNode> sorted, WorkflowConfig config) {
        List<String> order = ids(sorted);
        for (WorkflowEdge edge : config.getEdges()) {
            assertTrue(order.indexOf(edge.getSource()) < order.indexOf(edge.getTarget()),
                    edge.getSource() + " 必须排在 " + edge.getTarget() + " 之前，实际顺序: " + order);
        }
    }

    @Test
    void linearChainKeepsOrder() {
        WorkflowConfig config = config(List.of("c", "a", "b"), "a>b", "b>c");
        assertEquals(List.of("a", "b", "c"), ids(parser.parse(config)));
    }

    @Test
    void diamondRespectsDependencies() {
        WorkflowConfig config = config(List.of("d", "c", "b", "a"), "a>b", "a>c", "b>d", "c>d");
        List<WorkflowNode> sorted = parser.parse(config);
        assertEquals(4, sorted.size());
        assertRespectsDependencies(sorted, config);
        assertEquals("a", sorted.get(0).getId());
        assertEquals("d", sorted.get(3).getId());
    }

    @Test
    void multipleRootsAndIsolatedNodesAreAllIncluded() {
        WorkflowConfig config = config(List.of("r1", "r2", "x", "iso"), "r1>x", "r2>x");
        List<WorkflowNode> sorted = parser.parse(config);
        assertEquals(4, sorted.size());
        assertRespectsDependencies(sorted, config);
    }

    @Test
    void noEdgesReturnsAllNodes() {
        assertEquals(3, parser.parse(config(List.of("a", "b", "c"))).size());
    }

    @Test
    void directCycleIsRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> parser.parse(config(List.of("a", "b"), "a>b", "b>a")));
        assertTrue(e.getMessage().contains("循环依赖"));
    }

    @Test
    void selfLoopIsRejected() {
        assertThrows(RuntimeException.class, () -> parser.parse(config(List.of("a"), "a>a")));
    }

    @Test
    void longCycleBehindValidPrefixIsRejected() {
        WorkflowConfig config = config(List.of("in", "a", "b", "c"), "in>a", "a>b", "b>c", "c>a");
        assertThrows(RuntimeException.class, () -> parser.parse(config));
    }

    @Test
    void edgeReferencingMissingNodeGivesAClearError() {
        WorkflowConfig config = config(List.of("a", "b"), "a>b", "b>ghost");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parser.parse(config));
        assertTrue(e.getMessage().contains("ghost"), "错误信息应指出不存在的节点 id: " + e.getMessage());
    }

    @Test
    void levelsGroupIndependentNodesTogether() {
        WorkflowConfig config = config(List.of("in", "a", "b", "c", "out"),
                "in>a", "in>b", "in>c", "a>out", "b>out", "c>out");
        List<List<String>> levels = parser.groupIntoLevels(parser.parse(config), config.getEdges()).stream()
                .map(DAGParserTest::ids).collect(Collectors.toList());

        assertEquals(3, levels.size());
        assertEquals(List.of("in"), levels.get(0));
        assertEquals(3, levels.get(1).size());
        assertTrue(levels.get(1).containsAll(List.of("a", "b", "c")));
        assertEquals(List.of("out"), levels.get(2));
    }

    @Test
    void levelUsesLongestPathNotShortest() {
        // in -> a -> b -> out，同时 in -> out：out 必须在 b 之后（第 4 层），不能因为有一条短路径就提前
        WorkflowConfig config = config(List.of("in", "a", "b", "out"), "in>a", "a>b", "b>out", "in>out");
        List<List<String>> levels = parser.groupIntoLevels(parser.parse(config), config.getEdges()).stream()
                .map(DAGParserTest::ids).collect(Collectors.toList());
        assertEquals(List.of(List.of("in"), List.of("a"), List.of("b"), List.of("out")), levels);
    }
}
