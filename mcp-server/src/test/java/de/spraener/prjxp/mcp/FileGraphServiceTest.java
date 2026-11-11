package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileGraphServiceTest {

    @Mock
    private ProjectRegistry projectRegistry;
    @Mock
    private PxChunkDaoProvider provider;
    @Mock
    private PxChunkDao dao;
    @Mock
    private ProjectGraphStore projectGraphStore;

    private FileGraphService service;

    @BeforeEach
    void setUp() {
        service = new FileGraphService(projectRegistry, provider, projectGraphStore);
    }

    private CodeGraph graph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.9),
                        new GraphNode("demo.B", "src/B.java", "java", false, "c1", 0.2),
                        new GraphNode("demo.C", "src/C.java", "java", false, "c1", 0.1),
                        new GraphNode("demo.A#foo()", "src/A.java", "java", true, "c0", 0.4),
                        new GraphNode("demo.Other", "src/Other.java", "java", false, "c2", 0.3)),
                List.of(
                        new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false),
                        new GraphEdge("demo.A", "extern.Thing", EdgeType.FIELD, true),
                        new GraphEdge("demo.A#foo()", "demo.B", EdgeType.CALL, false),
                        new GraphEdge("demo.C", "demo.A", EdgeType.EXTENDS, false),
                        new GraphEdge("demo.A", "demo.C", EdgeType.IMPORT, false)));
    }

    private PxChunk chunk(String file, String graphNode) {
        return PxChunk.create(c -> {
            c.setId("chunk-" + file);
            c.setFile(file);
            if (graphNode != null) {
                c.getMetadata().put(GraphMetadata.GRAPH_NODE, graphNode);
            }
        });
    }

    private List<ScoredChunk> scored(PxChunk... chunks) {
        return Arrays.stream(chunks).map(c -> new ScoredChunk(c, 1.0)).toList();
    }

    /** Stubs the happy-path project wiring (searchable, graph present, dao present). */
    private void stubDemo() {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));
        when(provider.get("demo")).thenReturn(Optional.of(dao));
    }

    // ==================== graphFile ====================

    @Test
    void graphFileResolvesExactPathAndBuildsNeighbours() {
        stubDemo();
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "src/A.java")), anyInt()))
                .thenReturn(scored(chunk("src/A.java", "demo.A")));

        GraphView view = service.graphFile("src/A.java", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo("demo.A");
        assertThat(view.file()).isEqualTo("src/A.java");
        assertThat(view.project()).isEqualTo("demo");
        assertThat(view.outgoing())
                .extracting(GraphNeighbor::neighborId)
                .containsExactlyInAnyOrder("demo.B", "demo.C");
        assertThat(view.outgoing()).noneMatch(GraphNeighbor::external);
        assertThat(view.incoming())
                .extracting(GraphNeighbor::neighborId, GraphNeighbor::neighborFile)
                .containsExactly(tuple("demo.C", "src/C.java"));
    }

    @Test
    void graphFileResolvesByBasename() {
        stubDemo();
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "A.java")), anyInt())).thenReturn(List.of());
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "/A.java")), anyInt())).thenReturn(List.of());
        when(dao.searchByIndex(eq(Map.of()), anyInt()))
                .thenReturn(scored(chunk("src/A.java", "demo.A"), chunk("src/B.java", "demo.B")));

        GraphView view = service.graphFile("A.java", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo("demo.A");
        assertThat(view.file()).isEqualTo("src/A.java");
    }

    @Test
    void graphFileIncludesExternalWhenRequested() {
        stubDemo();
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "src/A.java")), anyInt()))
                .thenReturn(scored(chunk("src/A.java", "demo.A")));

        GraphView view = service.graphFile("src/A.java", "demo", 30, true);

        assertThat(view.outgoing()).anyMatch(n ->
                n.neighborId().equals("extern.Thing") && n.external() && n.neighborFile() == null);
    }

    @Test
    void graphFileCapsEdgesPerDirection() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(new GraphNode("hub.H", "src/H.java", "java", false, "c0", 1.0));
        for (int i = 0; i < 120; i++) {
            nodes.add(new GraphNode("hub.T" + i, "src/T" + i + ".java", "java", false, "c1", 0.1));
            edges.add(new GraphEdge("hub.H", "hub.T" + i, EdgeType.IMPORT, false));
        }
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(new CodeGraph(nodes, edges)));
        when(provider.get("demo")).thenReturn(Optional.of(dao));
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "src/H.java")), anyInt()))
                .thenReturn(scored(chunk("src/H.java", "hub.H")));

        assertThat(service.graphFile("src/H.java", "demo", 5, false).outgoing()).hasSize(5);
        assertThat(service.graphFile("src/H.java", "demo", null, false).outgoing()).hasSize(30);
        assertThat(service.graphFile("src/H.java", "demo", 500, false).outgoing()).hasSize(100);
    }

    @Test
    void graphFileAmbiguousPathReturnsCandidates() {
        stubDemo();
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "A.java")), anyInt())).thenReturn(List.of());
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "/A.java")), anyInt())).thenReturn(List.of());
        when(dao.searchByIndex(eq(Map.of()), anyInt()))
                .thenReturn(scored(chunk("src/x/A.java", "demo.A"), chunk("src/y/A.java", "demo.B")));

        GraphView view = service.graphFile("A.java", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.candidates()).containsExactlyInAnyOrder("src/x/A.java", "src/y/A.java");
    }

    @Test
    void graphFileUnknownProjectReturnsNotSearchable() {
        doThrow(new UnknownProjectException("nope", List.of("alpha", "beta"))).when(projectRegistry).ensureSearchable("nope");

        GraphView view = service.graphFile("src/A.java", "nope", 30, false);

        assertThat(view.error()).contains("No searchable store").contains("nope").contains("alpha");
    }

    @Test
    void graphFileMissingGraphReturnsUnavailable() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.empty());

        GraphView view = service.graphFile("src/A.java", "demo", 30, false);

        assertThat(view.error()).contains("Graph nicht verfügbar");
    }

    @Test
    void graphFileChunkWithoutGraphNodeReturnsUnavailable() {
        stubDemo();
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "src/A.java")), anyInt()))
                .thenReturn(scored(chunk("src/A.java", null)));

        GraphView view = service.graphFile("src/A.java", "demo", 30, false);

        assertThat(view.error()).contains("graph_node");
    }

    @Test
    void graphFileUnsupportedIndexSearchReturnsUnavailable() {
        stubDemo();
        when(dao.searchByIndex(anyMap(), anyInt())).thenThrow(new UnsupportedOperationException("no index"));

        GraphView view = service.graphFile("src/A.java", "demo", 30, false);

        assertThat(view.error()).contains("Index-Suche");
    }

    // ==================== graphNode ====================

    @Test
    void graphNodeResolvesExactId() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("demo.A", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo("demo.A");
        assertThat(view.file()).isEqualTo("src/A.java");
        assertThat(view.clusterId()).isEqualTo("c0");
        assertThat(view.weight()).isEqualTo(0.9);
    }

    @Test
    void graphNodeResolvesSuffix() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("A", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo("demo.A");
    }

    @Test
    void graphNodeResolvesMethodSuffix() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("A#foo()", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo("demo.A#foo()");
    }

    @Test
    void graphNodeAmbiguousReturnsCandidates() {
        CodeGraph g = new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.9),
                        new GraphNode("demo.pkg2.A", "src/pkg2/A.java", "java", false, "c1", 0.2)),
                List.of());
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(g));

        GraphView view = service.graphNode("A", "demo", 30, false);

        assertThat(view.error()).isNull();
        assertThat(view.candidates()).containsExactly("demo.A", "demo.pkg2.A");
    }

    @Test
    void graphNodeNotFoundReturnsError() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("Nope", "demo", 30, false);

        assertThat(view.error()).contains("nicht gefunden");
    }

    @Test
    void incomingNeighboursPointToSourceNode() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("demo.B", "demo", 30, false);

        assertThat(view.incoming())
                .extracting(GraphNeighbor::neighborId, GraphNeighbor::neighborFile)
                .containsExactlyInAnyOrder(
                        tuple("demo.A", "src/A.java"),
                        tuple("demo.A#foo()", "src/A.java"));
    }

    @Test
    void edgesAreFilteredForExternalByDefault() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("demo.A", "demo", null, null);

        // demo.A has 3 outgoing edges, one of them external -> 2 internal remain
        assertThat(view.outgoing()).hasSize(2);
        assertThat(view.outgoing()).noneMatch(GraphNeighbor::external);
    }

    // ==================== graphCluster ====================

    private CodeGraph clusterGraph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.9),
                        new GraphNode("demo.B", "src/B.java", "java", false, "c0", 0.5),
                        new GraphNode("demo.C", "src/C.java", "java", false, "c0", 0.3),
                        new GraphNode("demo.D", "src/D.java", "java", false, "c1", 0.8)),
                List.of(
                        new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false),
                        new GraphEdge("demo.A", "demo.C", EdgeType.IMPORT, false),
                        new GraphEdge("demo.B", "demo.A", EdgeType.CALL, false),
                        new GraphEdge("demo.C", "demo.A", EdgeType.EXTENDS, false),
                        new GraphEdge("demo.A", "extern.Thing", EdgeType.FIELD, true)));
    }

    @Test
    void graphClusterReturnsTopNByWeightDescending() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(clusterGraph()));

        ClusterView view = service.graphCluster("c0", "demo", 2);

        assertThat(view.error()).isNull();
        assertThat(view.project()).isEqualTo("demo");
        assertThat(view.clusterId()).isEqualTo("c0");
        assertThat(view.totalMembers()).isEqualTo(3);
        assertThat(view.members()).hasSize(2);
        assertThat(view.members())
                .extracting(ClusterMember::id, ClusterMember::weight)
                .containsExactly(tuple("demo.A", 0.9), tuple("demo.B", 0.5));
    }

    @Test
    void graphClusterCountsAllEdgesIncludingExternal() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(clusterGraph()));

        ClusterView view = service.graphCluster("c0", "demo", null);

        assertThat(view.members())
                .extracting(ClusterMember::id, ClusterMember::inDegree, ClusterMember::outDegree)
                .containsExactly(
                        // in: B->A, C->A; out: A->B, A->C, A->extern (external counted, matching weight)
                        tuple("demo.A", 2, 3),
                        tuple("demo.B", 1, 1),
                        tuple("demo.C", 1, 1));
    }

    @Test
    void graphClusterUnknownClusterReturnsError() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(clusterGraph()));

        ClusterView view = service.graphCluster("c9", "demo", null);

        assertThat(view.error()).contains("Cluster nicht gefunden").contains("c9");
        assertThat(view.members()).isEmpty();
    }

    @Test
    void graphClusterClampsTopNToMax() {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            nodes.add(new GraphNode("hub.M" + i, "src/M" + i + ".java", "java", false, "c0", 1.0 - i * 0.01));
        }
        nodes.add(new GraphNode("other.X", "src/X.java", "java", false, "c1", 0.5));
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(new CodeGraph(nodes, List.of())));

        ClusterView view = service.graphCluster("c0", "demo", 100);

        assertThat(view.totalMembers()).isEqualTo(60);
        assertThat(view.members()).hasSize(50);
    }

    @Test
    void graphClusterRequiresClusterId() {
        ClusterView view = service.graphCluster("  ", "demo", null);

        assertThat(view.error()).contains("clusterId");
        assertThat(view.members()).isEmpty();
    }

    @Test
    void graphClusterUnknownProjectReturnsError() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        ClusterView view = service.graphCluster("c0", "nope", null);

        assertThat(view.error()).contains("No searchable store").contains("nope");
    }

    @Test
    void graphClusterMissingGraphReturnsUnavailable() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.empty());

        ClusterView view = service.graphCluster("c0", "demo", null);

        assertThat(view.error()).contains("Graph nicht verfügbar");
    }
}
