package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
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

    /** A hub file with {@code n} outgoing IMPORT edges to {@code hub.T0..Tn-1} (insertion order). */
    private CodeGraph hubGraph(int n) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        nodes.add(new GraphNode("hub.H", "src/H.java", "java", false, "c0", 1.0));
        for (int i = 0; i < n; i++) {
            nodes.add(new GraphNode("hub.T" + i, "src/T" + i + ".java", "java", false, "c1", 0.1));
            edges.add(new GraphEdge("hub.H", "hub.T" + i, EdgeType.IMPORT, false));
        }
        return new CodeGraph(nodes, edges);
    }

    /** Stubs the hub-file lookup so {@code graphFile("src/H.java", ...)} resolves to {@code hub.H}. */
    private void stubHub(int n) {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(hubGraph(n)));
        when(provider.get("demo")).thenReturn(Optional.of(dao));
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "src/H.java")), anyInt()))
                .thenReturn(scored(chunk("src/H.java", "hub.H")));
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
        stubHub(120);

        assertThat(service.graphFile("src/H.java", "demo", 5, false).outgoing()).hasSize(5);
        assertThat(service.graphFile("src/H.java", "demo", null, false).outgoing()).hasSize(30);
        // cap raised 100 -> 500 (efficiency measure 2): all 120 edges now fit in one page
        assertThat(service.graphFile("src/H.java", "demo", 500, false).outgoing()).hasSize(120);
        assertThat(service.graphFile("src/H.java", "demo", 500, false).totalOutgoing()).isEqualTo(120);
    }

    // ---- Efficiency measure 2: offset pagination + full per-direction totals ----

    @Test
    void graphFilePagesWithOffset() {
        stubHub(120);

        GraphView view = service.graphFile("src/H.java", "demo", 5, false, 50);

        assertThat(view.outgoing()).hasSize(5);
        assertThat(view.outgoing())
                .extracting(GraphNeighbor::neighborId)
                .containsExactly("hub.T50", "hub.T51", "hub.T52", "hub.T53", "hub.T54");
        // totals are the full count, independent of the page
        assertThat(view.totalOutgoing()).isEqualTo(120);
        assertThat(view.totalIncoming()).isEqualTo(0);
    }

    @Test
    void graphFileOffsetBeyondEndReturnsEmptyPageWithTotals() {
        stubHub(120);

        GraphView view = service.graphFile("src/H.java", "demo", 30, false, 200);

        assertThat(view.outgoing()).isEmpty();
        assertThat(view.totalOutgoing()).isEqualTo(120);
    }

    @Test
    void graphFileNegativeOffsetTreatedAsZero() {
        stubHub(120);

        GraphView view = service.graphFile("src/H.java", "demo", 5, false, -10);

        assertThat(view.outgoing()).hasSize(5);
        assertThat(view.outgoing().get(0).neighborId()).isEqualTo("hub.T0");
    }

    @Test
    void graphFileCapsAt500ForVeryLargeHubs() {
        stubHub(600);

        GraphView view = service.graphFile("src/H.java", "demo", 1000, false);

        assertThat(view.outgoing()).hasSize(500);
        assertThat(view.totalOutgoing()).isEqualTo(600);
    }

    @Test
    void graphFileTotalsReflectExternalFilter() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(graph()));

        GraphView view = service.graphNode("demo.A", "demo", null, null);

        // demo.A has 3 outgoing edges, one external -> 2 internal; 1 incoming
        assertThat(view.totalOutgoing()).isEqualTo(2);
        assertThat(view.totalIncoming()).isEqualTo(1);
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

    // ---- Efficiency measure 5: contentLevel=AGGREGATED (module-level aggregation) ----

    /**
     * A hub node {@code core.Model} referenced from two modules: {@code core} (4 edges,
     * 2 classes — method ids collapse to their class) and {@code web} (3 edges, 3 classes),
     * plus one outgoing edge per module and one external edge.
     */
    private CodeGraph multiModuleGraph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("core.Model", "core/src/Model.java", "java", false, "c0", 1.0),
                        new GraphNode("core.A", "core/src/A.java", "java", false, "c1", 0.5),
                        new GraphNode("core.B", "core/src/B.java", "java", false, "c1", 0.4),
                        new GraphNode("web.C", "web/src/C.java", "java", false, "c2", 0.3),
                        new GraphNode("web.D", "web/src/D.java", "java", false, "c2", 0.2),
                        new GraphNode("web.E", "web/src/E.java", "java", false, "c2", 0.1),
                        new GraphNode("core.A#foo()", "core/src/A.java", "java", true, "c1", 0.3),
                        new GraphNode("core.A#bar()", "core/src/A.java", "java", true, "c1", 0.2)),
                List.of(
                        new GraphEdge("core.A", "core.Model", EdgeType.IMPORT, false),
                        new GraphEdge("core.B", "core.Model", EdgeType.IMPORT, false),
                        new GraphEdge("core.A#foo()", "core.Model", EdgeType.PARAM, false),
                        new GraphEdge("core.A#bar()", "core.Model", EdgeType.RETURN, false),
                        new GraphEdge("web.C", "core.Model", EdgeType.IMPORT, false),
                        new GraphEdge("web.D", "core.Model", EdgeType.CALL, false),
                        new GraphEdge("web.E", "core.Model", EdgeType.FIELD, false),
                        new GraphEdge("core.Model", "core.A", EdgeType.IMPORT, false),
                        new GraphEdge("core.Model", "web.C", EdgeType.IMPORT, false),
                        new GraphEdge("core.Model", "extern.Thing", EdgeType.FIELD, true)));
    }

    @Test
    void graphNodeAggregatedGroupsByModule() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", null, false, null, ContentLevel.AGGREGATED);

        assertThat(view.error()).isNull();
        // raw edge lists are empty in aggregated mode; the totals keep the full counts
        assertThat(view.outgoing()).isEmpty();
        assertThat(view.incoming()).isEmpty();
        assertThat(view.totalIncoming()).isEqualTo(7);
        assertThat(view.totalOutgoing()).isEqualTo(2);   // external edge filtered
        // modules sorted by edge count descending
        assertThat(view.incomingByModule())
                .extracting(GraphAggregate::module, GraphAggregate::edgeCount, GraphAggregate::classCount)
                .containsExactly(
                        tuple("core", 4, 2),
                        tuple("web", 3, 3));
        GraphAggregate core = view.incomingByModule().get(0);
        // method ids collapse to their class: A, A#foo(), A#bar() -> core.A(3)
        assertThat(core.topClasses())
                .extracting(GraphAggregate.ClassCount::className, GraphAggregate.ClassCount::count)
                .containsExactly(tuple("core.A", 3), tuple("core.B", 1));
        assertThat(core.typeCounts())
                .containsEntry(EdgeType.IMPORT, 2)
                .containsEntry(EdgeType.PARAM, 1)
                .containsEntry(EdgeType.RETURN, 1);
    }

    @Test
    void graphNodeAggregatedIgnoresPaging() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", 2, false, 5, ContentLevel.AGGREGATED);

        // maxEdges/offset must not truncate the aggregation — it covers all edges
        assertThat(view.incomingByModule())
                .extracting(GraphAggregate::edgeCount)
                .containsExactly(4, 3);
        assertThat(view.totalIncoming()).isEqualTo(7);
    }

    @Test
    void graphNodeAggregatedGroupsExternalUnderExternalModule() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", null, true, null, ContentLevel.AGGREGATED);

        // all three outgoing edges have count 1 -> ties break by module name
        assertThat(view.outgoingByModule())
                .extracting(GraphAggregate::module, GraphAggregate::edgeCount)
                .containsExactly(
                        tuple("(external)", 1),
                        tuple("core", 1),
                        tuple("web", 1));
    }

    @Test
    void graphNodeFullLevelLeavesByModuleNull() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", null, false, null, ContentLevel.FULL);

        assertThat(view.outgoingByModule()).isNull();
        assertThat(view.incomingByModule()).isNull();
        assertThat(view.incoming()).hasSize(7);
    }

    @Test
    void graphNodeNullContentLevelDefaultsToFull() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", null, false, null, null);

        assertThat(view.outgoingByModule()).isNull();
        assertThat(view.incoming()).hasSize(7);
    }

    @Test
    void graphNodeNonFullLevelAggregates() {
        // any non-FULL level (e.g. NAMES from the economical hint) yields the cheap aggregated view
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));

        GraphView view = service.graphNode("core.Model", "demo", null, false, null, ContentLevel.NAMES);

        assertThat(view.incomingByModule()).isNotNull();
        assertThat(view.incoming()).isEmpty();
    }

    @Test
    void graphFileAggregatedGroupsByModule() {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(multiModuleGraph()));
        when(provider.get("demo")).thenReturn(Optional.of(dao));
        when(dao.searchByIndex(eq(Map.of(PxChunk.PXCHUNK_FILE, "core/src/Model.java")), anyInt()))
                .thenReturn(scored(chunk("core/src/Model.java", "core.Model")));

        GraphView view = service.graphFile("core/src/Model.java", "demo", null, false, null, ContentLevel.AGGREGATED);

        assertThat(view.error()).isNull();
        assertThat(view.incomingByModule())
                .extracting(GraphAggregate::module, GraphAggregate::edgeCount)
                .containsExactly(tuple("core", 4), tuple("web", 3));
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

    // ==================== graphClusterOverview ====================

    private CodeGraph overviewGraph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.9),
                        new GraphNode("demo.B", "src/B.java", "java", false, "c0", 0.5),
                        new GraphNode("demo.C", "src/C.java", "java", false, "c0", 0.3),
                        new GraphNode("demo.D", "src/D.java", "java", false, "c1", 0.8),
                        new GraphNode("demo.E", "src/E.java", "java", false, "c1", 0.2),
                        new GraphNode("demo.F", "src/F.java", "java", false, "c2", 0.4),
                        new GraphNode("demo.G", "src/G.java", "java", false, null, 0.7)),
                List.of());
    }

    @Test
    void graphClusterOverviewReturnsTopNClustersBySize() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(overviewGraph()));

        ClusterOverviewView view = service.graphClusterOverview("demo", 2);

        assertThat(view.error()).isNull();
        assertThat(view.project()).isEqualTo("demo");
        assertThat(view.totalClusters()).isEqualTo(3);
        assertThat(view.clusters()).hasSize(2);
        assertThat(view.clusters())
                .extracting(ClusterSummary::clusterId, ClusterSummary::size)
                .containsExactly(tuple("c0", 3), tuple("c1", 2));
    }

    @Test
    void graphClusterOverviewTopMembersByWeightDescending() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(overviewGraph()));

        ClusterOverviewView view = service.graphClusterOverview("demo", null);

        assertThat(view.clusters().get(0).topMembers()).hasSizeLessThanOrEqualTo(3);
        assertThat(view.clusters().get(0).topMembers()).containsExactly("demo.A", "demo.B", "demo.C");
        assertThat(view.clusters().get(1).topMembers()).containsExactly("demo.D", "demo.E");
    }

    @Test
    void graphClusterOverviewExcludesNodesWithoutCluster() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(overviewGraph()));

        ClusterOverviewView view = service.graphClusterOverview("demo", null);

        // demo.G has a null clusterId and must not count as a cluster
        assertThat(view.totalClusters()).isEqualTo(3);
        assertThat(view.clusters()).extracting(ClusterSummary::clusterId).doesNotContainNull();
    }

    @Test
    void graphClusterOverviewClampsTopNToMax() {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            nodes.add(new GraphNode("hub.M" + i, "src/M" + i + ".java", "java", false, "c" + i, 1.0));
        }
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(new CodeGraph(nodes, List.of())));

        ClusterOverviewView view = service.graphClusterOverview("demo", 100);

        assertThat(view.totalClusters()).isEqualTo(60);
        assertThat(view.clusters()).hasSize(50);
    }

    @Test
    void graphClusterOverviewOrdersTiesDeterministically() {
        CodeGraph g = new CodeGraph(
                List.of(
                        new GraphNode("demo.B", "src/B.java", "java", false, "c1", 0.5),
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.5),
                        new GraphNode("demo.C", "src/C.java", "java", false, "c1", 0.5),
                        new GraphNode("demo.D", "src/D.java", "java", false, "c0", 0.5)),
                List.of());
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.of(g));

        ClusterOverviewView view = service.graphClusterOverview("demo", null);

        // equal sizes -> clusterId order; equal weights -> node id order
        assertThat(view.clusters()).extracting(ClusterSummary::clusterId).containsExactly("c0", "c1");
        assertThat(view.clusters().get(0).topMembers()).containsExactly("demo.A", "demo.D");
        assertThat(view.clusters().get(1).topMembers()).containsExactly("demo.B", "demo.C");
    }

    @Test
    void graphClusterOverviewUnknownProjectReturnsError() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        ClusterOverviewView view = service.graphClusterOverview("nope", null);

        assertThat(view.error()).contains("No searchable store").contains("nope");
        assertThat(view.clusters()).isEmpty();
    }

    @Test
    void graphClusterOverviewMissingGraphReturnsUnavailable() {
        when(projectGraphStore.graphFor("demo")).thenReturn(Optional.empty());

        ClusterOverviewView view = service.graphClusterOverview("demo", null);

        assertThat(view.error()).contains("Graph nicht verfügbar");
    }
}
