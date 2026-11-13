package de.spraener.prjxp.tibed.signature;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SignatureBatchTest {

    private static final String KEYWORDS_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + SignatureMetadata.KEYWORDS;
    private static final String CLUSTER_KEYWORDS_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + SignatureMetadata.CLUSTER_KEYWORDS;
    private static final String LEIDEN_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.LEIDEN_CLUSTER_ID;
    private static final String SEMANTIC_CLUSTER_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID;

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;
    private PrjXPConfig config;
    private SignatureBatch batch;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        config = new PrjXPConfig();
        config.getSignature().setTopN(3);
        config.getSignature().setMinTermLength(3);
        batch = new SignatureBatch(config);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void signsChunksWithKeywords() throws IOException {
        addChunk("p", "a", "jta jta jta connection pool", null);
        addChunk("p", "b", "hikari hikari datasource pooling", null);
        addChunk("p", "c", "lucene index segment merge", null);

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        assertThat(chunks).hasSize(3);
        assertThat(metadata(chunks, "a").get(KEYWORDS_FIELD)).startsWith("jta");
        assertThat(metadata(chunks, "b").get(KEYWORDS_FIELD)).isNotBlank();
        assertThat(metadata(chunks, "c").get(KEYWORDS_FIELD)).isNotBlank();
    }

    @Test
    void signsClustersWithClusterKeywords() throws IOException {
        addChunk("p", "a", "jta jta transaction manager", "c1");
        addChunk("p", "b", "jta connection pool", "c1");
        addChunk("p", "c", "lucene segment merge index", "c2");

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        String clusterKeywordsA = metadata(chunks, "a").get(CLUSTER_KEYWORDS_FIELD);
        assertThat(clusterKeywordsA).isNotBlank();
        assertThat(metadata(chunks, "b").get(CLUSTER_KEYWORDS_FIELD)).isEqualTo(clusterKeywordsA);
        assertThat(clusterKeywordsA.split(",")).contains("jta");
        assertThat(metadata(chunks, "c").get(CLUSTER_KEYWORDS_FIELD)).isNotBlank();
    }

    @Test
    void semanticClusterFallback() throws IOException {
        addChunk("p", "a", "jta jta transaction manager", null);
        addChunk("p", "b", "jta connection pool", null);
        setSemanticCluster("a", "s1");
        setSemanticCluster("b", "s1");

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        String clusterKeywords = metadata(chunks, "a").get(CLUSTER_KEYWORDS_FIELD);
        assertThat(clusterKeywords).isNotBlank();
        assertThat(clusterKeywords.split(",")).contains("jta");
        assertThat(metadata(chunks, "b").get(CLUSTER_KEYWORDS_FIELD)).isEqualTo(clusterKeywords);
    }

    @Test
    void leidenClusterWinsOverSemanticCluster() throws IOException {
        addChunk("p", "a", "jta jta transaction manager", "c1");
        addChunk("p", "b", "lucene segment merge", "c1");
        setSemanticCluster("a", "s-lonely");

        batch.run("p", store);

        List<VectorChunk> chunks = projectChunks("p");
        // cluster keywords come from c1 (both chunks), not from the singleton semantic cluster
        assertThat(metadata(chunks, "a").get(CLUSTER_KEYWORDS_FIELD))
                .isEqualTo(metadata(chunks, "b").get(CLUSTER_KEYWORDS_FIELD));
    }

    @Test
    void disabledConfigSkips() throws IOException {
        LuceneEmbeddingStore mockedStore = Mockito.mock(LuceneEmbeddingStore.class);
        config.getSignature().setEnabled(false);

        batch.run("p", mockedStore);

        Mockito.verifyNoInteractions(mockedStore);
    }

    @Test
    void emptyIndexNoException() {
        assertThatCode(() -> batch.run("p", store)).doesNotThrowAnyException();
        assertThatCode(() -> batch.run("missing", store)).doesNotThrowAnyException();
    }

    @Test
    void nullKeywordsForNoTerms() throws IOException {
        addChunk("p", "onlystopwords", "the the the and a is", null);

        batch.run("p", store);

        assertThat(metadata(projectChunks("p"), "onlystopwords"))
                .doesNotContainKey(KEYWORDS_FIELD)
                .doesNotContainKey(CLUSTER_KEYWORDS_FIELD);
    }

    @Test
    void chunkWithoutClusterGetsNoClusterKeywords() throws IOException {
        addChunk("p", "a", "jta jta transaction manager", null);

        batch.run("p", store);

        assertThat(metadata(projectChunks("p"), "a")).doesNotContainKey(CLUSTER_KEYWORDS_FIELD);
    }

    // ------------------------------------------------------------------ helpers

    private void addChunk(String project, String id, String content, String leidenCluster) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, "0");
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown");
        if (leidenCluster != null) {
            metadata.put(LEIDEN_FIELD, leidenCluster);
        }
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from(content, metadata));
    }

    /** Applies the semantic cluster via an upsert (chunks are immutable within the index). */
    private void setSemanticCluster(String chunkId, String cluster) {
        VectorChunk existing = projectChunks("p").stream()
                .filter(chunk -> chunk.id().equals(chunkId))
                .findFirst()
                .orElseThrow();
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put(SEMANTIC_CLUSTER_FIELD, cluster);
        store.upsertAll(List.of(new VectorChunk(existing.nodeId(), existing.id(), existing.project(),
                existing.part(), null, null, patch)));
    }

    private List<VectorChunk> projectChunks(String project) {
        try {
            return store.readAllVectors(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> metadata(List<VectorChunk> chunks, String id) {
        return chunks.stream()
                .filter(chunk -> chunk.id().equals(id))
                .findFirst()
                .orElseThrow()
                .metadata();
    }
}
