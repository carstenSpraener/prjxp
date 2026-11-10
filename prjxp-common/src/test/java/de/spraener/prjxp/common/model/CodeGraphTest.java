package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphTest {

    @Test
    void graphNodeFactoriesSetDefaults() {
        GraphNode type = GraphNode.type("de.spraener.Foo", "src/Foo.java", "java");
        assertThat(type.isMethod()).isFalse();
        assertThat(type.clusterId()).isNull();
        assertThat(type.weight()).isEqualTo(0.0);

        GraphNode method = GraphNode.method("de.spraener.Foo#bar(int)", "src/Foo.java", "java");
        assertThat(method.isMethod()).isTrue();
    }

    @Test
    void withRatingCopiesIdentityAndSetsRating() {
        GraphNode type = GraphNode.type("de.spraener.Foo", "src/Foo.java", "java");
        GraphNode rated = type.withRating("c3", 0.82);

        assertThat(rated.id()).isEqualTo("de.spraener.Foo");
        assertThat(rated.file()).isEqualTo("src/Foo.java");
        assertThat(rated.language()).isEqualTo("java");
        assertThat(rated.isMethod()).isFalse();
        assertThat(rated.clusterId()).isEqualTo("c3");
        assertThat(rated.weight()).isEqualTo(0.82);
        // original is immutable / unchanged
        assertThat(type.clusterId()).isNull();
    }

    @Test
    void nodeByIdIndexesByNodeId() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("de.spraener.Foo", "src/Foo.java", "java"),
                        GraphNode.method("de.spraener.Foo#bar", "src/Foo.java", "java")),
                List.of());

        assertThat(graph.nodeById()).containsKeys("de.spraener.Foo", "de.spraener.Foo#bar");
    }

    @Test
    void nodeByIdKeepsFirstOnDuplicateId() {
        GraphNode a = GraphNode.type("de.spraener.Foo", "a.java", "java").withRating("c1", 0.1);
        GraphNode b = GraphNode.type("de.spraener.Foo", "b.java", "java").withRating("c2", 0.9);
        CodeGraph graph = new CodeGraph(List.of(a, b), List.of());

        assertThat(graph.nodeById().get("de.spraener.Foo").file()).isEqualTo("a.java");
    }

    @Test
    void edgesGroupedBySourceAndTarget() {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("de.spraener.A", "a.java", "java"),
                        GraphNode.type("de.spraener.B", "b.java", "java")),
                List.of(
                        new GraphEdge("de.spraener.A", "de.spraener.B", EdgeType.IMPORT, false),
                        new GraphEdge("de.spraener.A", "de.spraener.B", EdgeType.CALL, false)));

        assertThat(graph.edgesBySource().get("de.spraener.A")).hasSize(2);
        assertThat(graph.edgesByTarget().get("de.spraener.B")).hasSize(2);
        assertThat(graph.edgesBySource().get("de.spraener.B")).isNull();
    }

    @Test
    void emptyGraphHasNoNodesOrEdges() {
        CodeGraph graph = CodeGraph.empty();
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
        assertThat(graph.nodeById()).isEmpty();
    }
}
