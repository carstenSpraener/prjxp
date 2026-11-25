package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphSearchServiceTest {

    @Mock
    private ProjectRegistry projectRegistry;
    @Mock
    private ProjectGraphStore graphStore;

    private GraphSearchService service;

    @BeforeEach
    void setUp() {
        service = new GraphSearchService(projectRegistry, graphStore);
    }

    private CodeGraph graph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.PxChunk", "src/PxChunk.java", "java", false, "c0", 1.0),
                        new GraphNode("demo.PxChunk#create()", "src/PxChunk.java", "java", true, "c0", 0.4),
                        new GraphNode("demo.PxChunk#value()", "src/PxChunk.java", "java", true, "c0", 0.3),
                        new GraphNode("demo.PxChunker", "src/PxChunker.java", "java", false, "c1", 0.5),
                        new GraphNode("demo.Other", "src/Other.java", "java", false, "c2", 0.2),
                        new GraphNode("demo.Deep", "src/Deep.java", "java", false, "c3", 0.1)),
                List.of(
                        new GraphEdge("demo.PxChunk", "demo.PxChunk#create()", EdgeType.CONTAINS, false),
                        new GraphEdge("demo.PxChunk", "demo.PxChunk#value()", EdgeType.CONTAINS, false),
                        new GraphEdge("demo.PxChunker", "demo.PxChunk", EdgeType.PARAM, false),
                        new GraphEdge("demo.Other", "demo.PxChunk", EdgeType.CALL, false),
                        new GraphEdge("demo.Deep", "demo.Other", EdgeType.CALL, false)));
    }

    @Test
    void labelMatchFindsTheClassAsSeed() {
        when(graphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphSearchView view = service.search("demo", "PxChunk", "bfs", 3, 2000, Set.of(), 3);

        assertThat(view.error()).isNull();
        assertThat(view.seeds()).contains("demo.PxChunk");
        assertThat(view.nodes()).extracting(GraphSearchView.GraphSearchNode::id).contains("demo.PxChunk");
    }

    @Test
    void bfsDepthControlsTheSubgraphSize() {
        when(graphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphSearchView shallow = service.search("demo", "PxChunk", "bfs", 1, 4000, Set.of(), 1);
        GraphSearchView deep = service.search("demo", "PxChunk", "bfs", 3, 4000, Set.of(), 1);

        assertThat(deep.nodes().size()).isGreaterThan(shallow.nodes().size());
    }

    @Test
    void contextFilterLimitsTheReturnedEdges() {
        when(graphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphSearchView view = service.search("demo", "PxChunk", "bfs", 3, 4000, Set.of(EdgeType.CONTAINS), 1);

        assertThat(view.edges()).isNotEmpty();
        assertThat(view.edges()).allMatch(edge -> edge.type() == EdgeType.CONTAINS);
        // the method neighbours are still reachable through the CONTAINS edges
        assertThat(view.nodes()).extracting(GraphSearchView.GraphSearchNode::id)
                .contains("demo.PxChunk#create()", "demo.PxChunk#value()");
    }

    @Test
    void tokenBudgetTrimsAndMarksTheViewAsTruncated() {
        when(graphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphSearchView view = service.search("demo", "PxChunk", "bfs", 3, 200, Set.of(), 1);

        assertThat(view.truncated()).isTrue();
        assertThat(view.totalNodes()).isGreaterThanOrEqualTo(view.nodes().size());
        assertThat(view.totalEdges()).isGreaterThanOrEqualTo(view.edges().size());
    }

    @Test
    void unknownProjectReturnsAnError() {
        org.mockito.Mockito.doThrow(new UnknownProjectException("nope", List.of("demo")))
                .when(projectRegistry).ensureSearchable("nope");

        GraphSearchView view = service.search("nope", "PxChunk", "bfs", 3, 2000, Set.of(), 3);

        assertThat(view.error()).contains("nope");
    }

    @Test
    void missingGraphReturnsUnavailable() {
        when(graphStore.graphFor("demo")).thenReturn(Optional.empty());

        GraphSearchView view = service.search("demo", "PxChunk", "bfs", 3, 2000, Set.of(), 3);

        assertThat(view.error()).contains("Graph nicht verfügbar");
    }

    @Test
    void tokenizerDropsGermanAndEnglishStopwords() {
        assertThat(GraphSearchService.tokenize("Wie arbeitet der PxChunk mit dem Chunker?"))
                .containsExactlyInAnyOrder("arbeitet", "pxchunk", "chunker");
    }
}
