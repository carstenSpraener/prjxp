package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DegreeCentralityTest {

    private CodeGraph graph(List<GraphEdge> edges) {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.0),
                        new GraphNode("demo.B", "src/B.java", "java", false, "c0", 0.0),
                        new GraphNode("demo.A#foo()", "src/A.java", "java", true, "c0", 0.0)),
                edges);
    }

    @Test
    void structuralEdgesDoNotChangeWeights() {
        CodeGraph withoutStructural = graph(List.of(
                new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false)));

        CodeGraph withStructural = graph(List.of(
                new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false),
                new GraphEdge("demo.A", "demo.A#foo()", EdgeType.CONTAINS, false),
                new GraphEdge("demo.A#foo()", "demo.B", EdgeType.GENERIC_ARG, false),
                new GraphEdge("demo.A", "demo.SomeAnnotation", EdgeType.ANNOTATION, true)));

        Map<String, Double> before = DegreeCentrality.weights(withoutStructural);
        Map<String, Double> after = DegreeCentrality.weights(withStructural);

        assertThat(after).isEqualTo(before);
        assertThat(after.get("demo.A#foo()")).isEqualTo(0.0);
    }

    @Test
    void callEdgesStillCount() {
        CodeGraph graph = graph(List.of(
                new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false),
                new GraphEdge("demo.A", "demo.A#foo()", EdgeType.CALL, false)));

        Map<String, Double> weights = DegreeCentrality.weights(graph);

        // A has degree 2 (IMPORT out, CALL out); foo has degree 1; B has degree 1
        assertThat(weights.get("demo.A")).isEqualTo(1.0);
        assertThat(weights.get("demo.A#foo()")).isEqualTo(0.5);
    }
}
