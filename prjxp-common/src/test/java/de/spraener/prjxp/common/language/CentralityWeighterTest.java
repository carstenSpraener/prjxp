package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CentralityWeighterTest {

    private final CentralityWeighter weighter = new CentralityWeighter();

    @Test
    void higherDegreeGetsHigherWeight() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java"),
                        GraphNode.type("C", "c.java", "java"),
                        GraphNode.type("D", "d.java", "java")),
                List.of(
                        new GraphEdge("A", "B", EdgeType.IMPORT, false),
                        new GraphEdge("A", "C", EdgeType.IMPORT, false),
                        new GraphEdge("A", "D", EdgeType.IMPORT, false)));

        Map<String, GraphNode> byId = weighter.apply(graph).nodeById();

        assertThat(byId.get("A").weight()).isEqualTo(1.0);          // max degree (3)
        assertThat(byId.get("B").weight()).isEqualTo(1.0 / 3.0);    // degree 1 / max 3
        assertThat(byId.get("A").weight()).isGreaterThan(byId.get("B").weight());
    }

    @Test
    void connectedNodesShareClusterId() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java"),
                        GraphNode.type("C", "c.java", "java")),
                List.of(
                        new GraphEdge("A", "B", EdgeType.IMPORT, false),
                        new GraphEdge("B", "C", EdgeType.CALL, false)));

        Map<String, GraphNode> byId = weighter.apply(graph).nodeById();

        assertThat(byId.get("A").clusterId()).isEqualTo(byId.get("B").clusterId());
        assertThat(byId.get("B").clusterId()).isEqualTo(byId.get("C").clusterId());
    }

    @Test
    void disconnectedComponentsGetDifferentClusterIds() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("A", "a.java", "java"),
                        GraphNode.type("B", "b.java", "java"),
                        GraphNode.type("C", "c.java", "java"),
                        GraphNode.type("D", "d.java", "java")),
                List.of(
                        new GraphEdge("A", "B", EdgeType.IMPORT, false),
                        new GraphEdge("C", "D", EdgeType.IMPORT, false)));

        Map<String, GraphNode> byId = weighter.apply(graph).nodeById();

        assertThat(byId.get("A").clusterId()).isEqualTo(byId.get("B").clusterId());
        assertThat(byId.get("C").clusterId()).isEqualTo(byId.get("D").clusterId());
        assertThat(byId.get("A").clusterId()).isNotEqualTo(byId.get("C").clusterId());
    }

    @Test
    void isolatedNodeGetsZeroWeightAndOwnCluster() {
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
    void emptyGraphStaysEmpty() {
        CodeGraph rated = weighter.apply(CodeGraph.empty());
        assertThat(rated.nodes()).isEmpty();
        assertThat(rated.edges()).isEmpty();
    }

    @Test
    void applyIsIdempotentOnRatings() {
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "a.java", "java"), GraphNode.type("B", "b.java", "java")),
                List.of(new GraphEdge("A", "B", EdgeType.IMPORT, false)));

        Map<String, GraphNode> first = weighter.apply(graph).nodeById();
        Map<String, GraphNode> second = weighter.apply(weighter.apply(graph)).nodeById();

        assertThat(second.get("A").weight()).isEqualTo(first.get("A").weight());
        assertThat(second.get("A").clusterId()).isEqualTo(first.get("A").clusterId());
    }
}
