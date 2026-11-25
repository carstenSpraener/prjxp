package de.spraener.prjxp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SemanticGraphSidecar;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SemanticGraphServiceTest {

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;
    private ProjectRegistry projectRegistry;
    private PrjXPConfig config;
    private ObjectMapper objectMapper;
    private SemanticGraphService service;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        projectRegistry = mock(ProjectRegistry.class);
        config = new PrjXPConfig();
        config.getEmbeddingStoreLucene().setIndexPath(tempDir.resolve("index").toString());
        config.getEmbedding().setModelName("test-model");
        objectMapper = new ObjectMapper();
        service = new SemanticGraphService(store, projectRegistry, config, objectMapper);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void semanticClusterReturnsMembersAndScopesByProject() {
        addChunk("p", "p1", 0, new float[]{1f, 0f, 0f}, "s1");
        addChunk("p", "p2", 0, new float[]{1f, 0f, 0f}, "s1");
        addChunk("q", "q1", 0, new float[]{1f, 0f, 0f}, "s1");
        addChunk("p", "p3", 0, new float[]{0f, 1f, 0f}, "s2");

        SemanticClusterView view = service.semanticCluster("s1", "p", 10);

        assertThat(view.error()).isNull();
        assertThat(view.graphKind()).isEqualTo("semantic");
        assertThat(view.totalMembers()).isEqualTo(2);
        assertThat(view.members()).extracting(SemanticMember::id)
                .containsExactlyInAnyOrder(nodeId("p", "p1", 0), nodeId("p", "p2", 0));
    }

    @Test
    void semanticClusterUnknownReturnsUnavailable() {
        SemanticClusterView view = service.semanticCluster("missing", "p", 10);

        assertThat(view.error()).contains("Cluster nicht gefunden");
        assertThat(view.members()).isEmpty();
        assertThat(service.semanticCluster(" ", "p", 10).error()).contains("clusterId");
    }

    @Test
    void semanticNodeReturnsOnlyProjectScopedNearestNeighbors() {
        addChunk("p", "center", 0, new float[]{1f, 0f, 0f}, "s1");
        addChunk("p", "similar", 0, new float[]{1f, 0.05f, 0f}, "s1");
        addChunk("p", "orthogonal", 0, new float[]{0f, 1f, 0f}, "s2");
        addChunk("q", "foreign", 0, new float[]{1f, 0f, 0f}, "s9");

        SemanticNodeView view = service.semanticNode(nodeId("p", "center", 0), "p", 10);

        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isEqualTo(nodeId("p", "center", 0));
        assertThat(view.clusterId()).isEqualTo("s1");
        assertThat(view.file()).isEqualTo("center.md");
        assertThat(view.mime()).isEqualTo("text/markdown");
        assertThat(view.neighbors()).extracting(SemanticNeighbor::id)
                .containsExactly(nodeId("p", "similar", 0));
        assertThat(view.neighbors()).allSatisfy(neighbor ->
                assertThat(neighbor.score()).isGreaterThan(0.85));
    }

    @Test
    void semanticNodeUnknownOrForeignProjectReturnsUnavailable() {
        addChunk("q", "only-q", 0, new float[]{1f, 0f, 0f}, "s1");

        assertThat(service.semanticNode(nodeId("p", "missing", 0), "p", 10).error())
                .contains("Chunk nicht gefunden");
        assertThat(service.semanticNode(nodeId("q", "only-q", 0), "p", 10).error())
                .contains("Chunk nicht gefunden");
        assertThat(service.semanticNode(" ", "p", 10).error())
                .contains("nodeId");
    }

    @Test
    void semanticOverviewSortsClustersAndReportsModelAndSidecarWarnings() throws IOException {
        addChunk("p", "large-a", 0, new float[]{1f, 0f, 0f}, "large");
        addChunk("p", "large-b", 0, new float[]{1f, 0f, 0f}, "large");
        addChunk("p", "small", 0, new float[]{0f, 1f, 0f}, "small");

        Path sidecarPath = tempDir.resolve("semantic-graph-p.json");
        objectMapper.writeValue(sidecarPath.toFile(),
                new SemanticGraphSidecar("test-model", 0.1, 0.85, 20, true, 3, "now"));
        SemanticOverviewView current = service.semanticOverview("p", 10);

        assertThat(current.error()).isNull();
        assertThat(current.totalClusters()).isEqualTo(2);
        assertThat(current.model()).isEqualTo("test-model");
        assertThat(current.warning()).isNull();
        assertThat(current.clusters()).extracting(SemanticClusterSummary::clusterId)
                .containsExactly("large", "small");
        assertThat(current.clusters().get(0).size()).isEqualTo(2);

        objectMapper.writeValue(sidecarPath.toFile(),
                new SemanticGraphSidecar("old-model", 0.1, 0.85, 20, true, 3, "now"));
        assertThat(service.semanticOverview("p", 10).warning()).contains("old-model");
        doThrow(new UnknownProjectException("unknown", List.of("p")))
                .when(projectRegistry).ensureSearchable("unknown");
        assertThat(service.semanticOverview("unknown", 10).error()).contains("project");
    }

    @Test
    void semanticOverviewWarnsWhenSidecarIsMissingOrInvalid() throws IOException {
        addChunk("p", "a", 0, new float[]{1f, 0f, 0f}, "s1");
        assertThat(service.semanticOverview("p", 10).warning()).contains("missing");

        Path sidecarPath = tempDir.resolve("semantic-graph-p.json");
        Files.writeString(sidecarPath, "{invalid json");
        assertThat(service.semanticOverview("p", 10).warning()).contains("Could not read");
    }

    private void addChunk(String project, String id, int part, float[] vector, String clusterId) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, Integer.toString(part));
        metadata.put(PxChunk.PXCHUNK_FILE, id + ".md");
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown");
        metadata.put(PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_NODE_ID,
                nodeId(project, id, part));
        if (clusterId != null) {
            metadata.put(PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID, clusterId);
        }
        store.add(Embedding.from(vector), TextSegment.from("content-" + id, metadata));
    }

    private String nodeId(String project, String id, int part) {
        return VectorChunk.nodeIdFor(project, id, part);
    }
}
