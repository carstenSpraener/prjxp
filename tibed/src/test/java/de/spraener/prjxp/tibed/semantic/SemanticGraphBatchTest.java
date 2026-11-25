package de.spraener.prjxp.tibed.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SemanticGraphSidecar;
import de.spraener.prjxp.common.language.LeidenWeighter;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SemanticGraphBatchTest {

    private static final String NODE_ID_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_NODE_ID;
    private static final String CLUSTER_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID;
    private static final String NEIGHBOURS_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + SignatureMetadata.SEMANTIC_NEIGHBOURS;

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;
    private PrjXPConfig config;
    private SemanticGraphBatch batch;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        config = new PrjXPConfig();
        config.getEmbeddingStoreLucene().setIndexPath(tempDir.resolve("index").toString());
        config.getEmbedding().setModelName("test-model");
        config.getSemanticGraph().setGamma(0.1);
        config.getSemanticGraph().setThreshold(0.85);
        config.getSemanticGraph().setK(10);
        config.getSemanticGraph().setRequireMutual(true);
        batch = new SemanticGraphBatch(
                new SemanticEdgeBuilder(), new LeidenWeighter(), config, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void runWritesMultiMemberClustersAndSidecar() throws IOException {
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "b", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "c", new float[]{1f, 0f, 0f}, "text/plain", null, null);
        addChunk("p", "isolated", new float[]{0f, 1f, 0f}, "text/markdown", null, null);

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        String cluster = metadata(chunks, "a").get(CLUSTER_FIELD);
        assertThat(cluster).isNotBlank();
        assertThat(metadata(chunks, "b").get(CLUSTER_FIELD)).isEqualTo(cluster);
        assertThat(metadata(chunks, "c").get(CLUSTER_FIELD)).isEqualTo(cluster);
        assertThat(metadata(chunks, "isolated")).doesNotContainKey(CLUSTER_FIELD);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata().get(NODE_ID_FIELD)).isEqualTo(chunk.nodeId()));

        Path sidecarPath = tempDir.resolve("semantic-graph-p.json");
        assertThat(Files.isRegularFile(sidecarPath)).isTrue();
        SemanticGraphSidecar sidecar = new ObjectMapper().readValue(
                sidecarPath.toFile(), SemanticGraphSidecar.class);
        assertThat(sidecar.model()).isEqualTo("test-model");
        assertThat(sidecar.gamma()).isEqualTo(0.1);
        assertThat(sidecar.threshold()).isEqualTo(0.85);
        assertThat(sidecar.k()).isEqualTo(10);
        assertThat(sidecar.chunkCount()).isEqualTo(4);
    }

    @Test
    void runClearsStaleIdsOnSingletonExcludedAndEmptyInput() throws IOException {
        addChunk("p", "singleton", new float[]{1f, 0f, 0f}, "text/markdown",
                CLUSTER_FIELD, "old-single");
        addChunk("p", "method", new float[]{0f, 1f, 0f}, "text/x-java-code",
                PxChunk.PXCHUNK_METADATA + ".java_code_section", "method");

        batch.run("p", store);

        assertThat(metadata(projectChunks("p"), "singleton")).doesNotContainKey(CLUSTER_FIELD);
        assertThat(metadata(projectChunks("p"), "method")).doesNotContainKey(CLUSTER_FIELD);

        store.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p"));
        addChunk("p", "unsupported", new float[]{0f, 0f, 1f}, "file/text",
                CLUSTER_FIELD, "stale");
        batch.run("p", store);
        assertThat(metadata(projectChunks("p"), "unsupported")).doesNotContainKey(CLUSTER_FIELD);
    }

    @Test
    void calibrationDoesNotWriteIndexMetadataOrSidecar() throws IOException {
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        config.getSemanticGraph().setCalibrate(true);

        batch.run("p", store);

        assertThat(metadata(projectChunks("p"), "a")).doesNotContainKey(CLUSTER_FIELD);
        assertThat(Files.exists(tempDir.resolve("semantic-graph-p.json"))).isFalse();
    }

    @Test
    void projectScopedUpsertDoesNotTouchSameIdInAnotherProject() throws IOException {
        addChunk("p", "same", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("q", "same", new float[]{0f, 1f, 0f}, "text/markdown",
                CLUSTER_FIELD, "q-old");

        batch.run("p", store);

        List<VectorChunk> qChunks = projectChunks("q");
        assertThat(qChunks).hasSize(1);
        assertThat(qChunks.get(0).vector()).containsExactly(0f, 1f, 0f);
        assertThat(qChunks.get(0).metadata()).containsEntry(CLUSTER_FIELD, "q-old");
    }

    @Test
    void writesSemanticNeighboursForInputChunks() throws IOException {
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "b", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "c", new float[]{1f, 0f, 0f}, "text/markdown", null, null);

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        assertThat(neighbours(metadata(chunks, "a"))).containsExactlyInAnyOrder("b", "c");
        assertThat(neighbours(metadata(chunks, "b"))).containsExactlyInAnyOrder("a", "c");
        assertThat(neighbours(metadata(chunks, "c"))).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void deletesSemanticNeighboursForNonInputChunks() throws IOException {
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        Map<String, String> methodMetadata = new java.util.LinkedHashMap<>();
        methodMetadata.put(PxChunk.PXCHUNK_METADATA + ".java_code_section", "method");
        methodMetadata.put(NEIGHBOURS_FIELD, "stale");
        addChunk("p", "method", new float[]{0f, 1f, 0f}, "text/x-java-code", methodMetadata);

        batch.run("p", store);

        assertThat(metadata(projectChunks("p"), "method")).doesNotContainKey(NEIGHBOURS_FIELD);
    }

    @Test
    void capsAtTenNeighbours() throws IOException {
        config.getSemanticGraph().setK(20);
        for (int i = 0; i < 16; i++) {
            addChunk("p", "n" + i, new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        }

        batch.run("p", store);

        String neighbours = metadata(projectChunks("p"), "n0").get(NEIGHBOURS_FIELD);
        assertThat(neighbours.split(java.util.regex.Pattern.quote(SignatureMetadata.LIST_SEPARATOR)))
                .hasSize(SemanticGraphBatch.MAX_SEMANTIC_NEIGHBOURS);
    }

    @Test
    void propagatesIndexReadFailures() throws IOException {        LuceneEmbeddingStore failingStore = Mockito.mock(LuceneEmbeddingStore.class);
        when(failingStore.readAllVectors(any())).thenThrow(new IOException("read failed"));

        assertThatThrownBy(() -> batch.run("p", failingStore))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("read failed");
    }

    private void addChunk(String project, String id, float[] vector, String mime,
                          String metadataKey, String metadataValue) {
        Map<String, String> extra = new java.util.LinkedHashMap<>();
        if (metadataKey != null) {
            extra.put(metadataKey, metadataValue);
        }
        addChunk(project, id, vector, mime, extra);
    }

    private void addChunk(String project, String id, float[] vector, String mime,
                          Map<String, String> extraMetadata) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, "0");
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, mime);
        metadata.put(PxChunk.PXCHUNK_FILE, id + ".md");
        metadata.put(PxChunk.PXCHUNK_METADATA + ".semantic_node_id",
                VectorChunk.nodeIdFor(project, id, 0));
        extraMetadata.forEach(metadata::put);
        store.add(Embedding.from(vector), TextSegment.from(id, metadata));
    }

    private List<VectorChunk> projectChunks(String project) throws IOException {
        return store.readAllVectors(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project));
    }

    private static List<String> neighbours(Map<String, String> metadata) {
        String value = metadata.get(NEIGHBOURS_FIELD);
        return value == null ? List.of() : List.of(value.split(java.util.regex.Pattern.quote(SignatureMetadata.LIST_SEPARATOR)));
    }

    private Map<String, String> metadata(List<VectorChunk> chunks, String id) {
        return chunks.stream()
                .filter(chunk -> chunk.id().equals(id))
                .findFirst()
                .orElseThrow()
                .metadata();
    }
}
