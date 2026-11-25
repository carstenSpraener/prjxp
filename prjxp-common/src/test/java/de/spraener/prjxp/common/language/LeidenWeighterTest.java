package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LeidenWeighterTest {

    private final LeidenWeighter weighter = new LeidenWeighter();

    /** Two triangles (A,B,C) and (D,E,F) joined by bridge node G (edges G-C, G-D). */
    private static CodeGraph twoTrianglesWithBridge() {
        return new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java"),
                        GraphNode.type("C", "c.java", "java"),
                        GraphNode.type("D", "d.java", "java"),
                        GraphNode.type("E", "e.java", "java"),
                        GraphNode.type("F", "f.java", "java"),
                        GraphNode.type("G", "g.java", "java")),
                List.of(
                        new GraphEdge("A", "B", EdgeType.IMPORT, false),
                        new GraphEdge("B", "C", EdgeType.IMPORT, false),
                        new GraphEdge("C", "A", EdgeType.IMPORT, false),
                        new GraphEdge("D", "E", EdgeType.IMPORT, false),
                        new GraphEdge("E", "F", EdgeType.IMPORT, false),
                        new GraphEdge("F", "D", EdgeType.IMPORT, false),
                        new GraphEdge("G", "C", EdgeType.CALL, false),
                        new GraphEdge("G", "D", EdgeType.CALL, false)));
    }

    /** Path A-B-C with G connected to all three: G has the maximum degree (3). */
    private static CodeGraph hubWithMaxDegree() {
        return new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java"),
                        GraphNode.type("C", "c.java", "java"),
                        GraphNode.type("G", "g.java", "java")),
                List.of(
                        new GraphEdge("A", "B", EdgeType.IMPORT, false),
                        new GraphEdge("B", "C", EdgeType.IMPORT, false),
                        new GraphEdge("G", "A", EdgeType.CALL, false),
                        new GraphEdge("G", "B", EdgeType.CALL, false),
                        new GraphEdge("G", "C", EdgeType.CALL, false)));
    }

    @Test
    void twoCommunitiesGetDifferentClusterIds() {
        Map<String, GraphNode> byId = weighter.apply(twoTrianglesWithBridge()).nodeById();

        Set<String> distinct = byId.values().stream()
                .map(GraphNode::clusterId)
                .collect(Collectors.toSet());
        assertThat(distinct).hasSizeGreaterThanOrEqualTo(2);
        assertThat(byId.values()).allSatisfy(n -> assertThat(n.clusterId()).isNotNull());
    }

    @Test
    void weightsAreDegreeCentralityInUnitRange() {
        Map<String, GraphNode> byId = weighter.apply(hubWithMaxDegree()).nodeById();

        assertThat(byId.values())
                .allSatisfy(n -> assertThat(n.weight()).isBetween(0.0, 1.0));
        // G has the maximum degree (3) → weight 1.0; A has degree 2 → 2/3.
        assertThat(byId.get("G").weight()).isGreaterThan(1.0 - 1e-9);
        assertThat(byId.get("G").weight()).isGreaterThan(byId.get("A").weight());
        assertThat(byId.get("A").weight()).isGreaterThan(0.0);
    }

    @Test
    void applyIsDeterministic() {
        CodeGraph graph = twoTrianglesWithBridge();
        Map<String, GraphNode> first = weighter.apply(graph).nodeById();
        Map<String, GraphNode> second = weighter.apply(graph).nodeById();

        for (String id : first.keySet()) {
            assertThat(second.get(id).clusterId())
                    .as("clusterId of %s", id)
                    .isEqualTo(first.get(id).clusterId());
            assertThat(second.get(id).weight()).isEqualTo(first.get(id).weight());
        }
    }

    @Test
    void emptyGraphStaysEmpty() {
        CodeGraph rated = weighter.apply(CodeGraph.empty());
        assertThat(rated.nodes()).isEmpty();
        assertThat(rated.edges()).isEmpty();
    }

    @Test
    void singleNodeGetsClusterC0AndZeroWeight() {
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "a.java", "java")),
                List.of());

        GraphNode rated = weighter.apply(graph).nodes().get(0);

        assertThat(rated.clusterId()).isEqualTo("c0");
        assertThat(rated.weight()).isEqualTo(0.0);
    }

    @Test
    void isolatedNodesGetOwnClusters() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java")),
                List.of());

        Map<String, GraphNode> byId = weighter.apply(graph).nodeById();

        assertThat(byId.get("A").weight()).isEqualTo(0.0);
        assertThat(byId.get("B").weight()).isEqualTo(0.0);
        assertThat(byId.get("A").clusterId()).isNotEqualTo(byId.get("B").clusterId());
    }

    @Test
    void sparseCliquesDoNotCollapseToSingletons() {
        // Fixed CPM resolution 1.0 yielded 100 singleton clusters for this shape;
        // the adaptive 2/sqrt(n) keeps the cliques intact.
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int c = 0; c < 10; c++) {
            for (int i = 0; i < 10; i++) {
                nodes.add(GraphNode.type("n" + c + "_" + i, "f.java", "java"));
            }
            for (int i = 0; i < 10; i++) {
                for (int j = i + 1; j < 10; j++) {
                    edges.add(new GraphEdge("n" + c + "_" + i, "n" + c + "_" + j, EdgeType.IMPORT, false));
                }
            }
        }

        Map<String, GraphNode> byId = weighter.apply(new CodeGraph(nodes, edges)).nodeById();

        assertThat(byId.values())
                .allSatisfy(n -> assertThat(n.weight()).isBetween(0.0, 1.0));

        // All 10 nodes of a clique share the same clusterId ...
        for (int c = 0; c < 10; c++) {
            String cluster = byId.get("n" + c + "_0").clusterId();
            for (int i = 1; i < 10; i++) {
                assertThat(byId.get("n" + c + "_" + i).clusterId())
                        .as("clique %d, node %d", c, i)
                        .isEqualTo(cluster);
            }
        }
        // ... and the 10 cliques have pairwise-distinct clusterIds.
        Set<String> clusters = new HashSet<>();
        for (int c = 0; c < 10; c++) {
            clusters.add(byId.get("n" + c + "_0").clusterId());
        }
        assertThat(clusters).hasSize(10);
    }

    @Test
    void nodesUnchangedBesidesRating() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("de.spraener.Foo", "src/Foo.java", "java"),
                        GraphNode.method("de.spraener.Foo#bar", "src/Foo.java", "java")),
                List.of(new GraphEdge("de.spraener.Foo", "de.spraener.Foo#bar", EdgeType.CALL, false)));

        CodeGraph rated = weighter.apply(graph);

        assertThat(rated.edges()).isEqualTo(graph.edges());
        for (int i = 0; i < graph.nodes().size(); i++) {
            GraphNode before = graph.nodes().get(i);
            GraphNode after = rated.nodes().get(i);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.file()).isEqualTo(before.file());
            assertThat(after.language()).isEqualTo(before.language());
            assertThat(after.isMethod()).isEqualTo(before.isMethod());
        }
    }
}
