package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TfIdfSignerTest {

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;
    private TfIdfSigner signer;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        signer = new TfIdfSigner(new TfIdfSigner.SignerConfig(3, 3));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void signChunksTopTerms() throws IOException {
        addChunk("p", "c1", 0, "jta jta jta two-phase-commit connection pool");
        addChunk("p", "c2", 0, "jta hikari hikari hikari connection pool");
        addChunk("q", "c3", 0, "zzz zzz zzz");

        Map<String, List<String>> signatures = signer.signChunks(store, "p");

        assertThat(signatures).containsOnlyKeys("c1", "c2");
        // StandardAnalyzer splits "two-phase-commit" into "two", "phase", "commit".
        assertThat(signatures.get("c1")).hasSize(3)
                .contains("jta", "commit", "phase");
        assertThat(signatures.get("c1").get(0)).isEqualTo("jta");
        assertThat(signatures.get("c2").get(0)).isEqualTo("hikari");
        assertThat(signatures).doesNotContainKey("c3");
    }

    @Test
    void signChunksAggregatesParts() throws IOException {
        addChunk("p", "c1", 0, "jta jta");
        addChunk("p", "c1", 1, "jta pool");

        Map<String, List<String>> signatures = signer.signChunks(store, "p");

        assertThat(signatures).containsOnlyKeys("c1");
        assertThat(signatures.get("c1").get(0)).isEqualTo("jta");
        assertThat(signatures.get("c1")).contains("pool");
    }

    @Test
    void signChunksFiltersStopwordsAndShortTerms() throws IOException {
        addChunk("p", "c1", 0, "the the the a is of jta");

        Map<String, List<String>> signatures = signer.signChunks(store, "p");

        assertThat(signatures.get("c1")).containsExactly("jta");
    }

    @Test
    void signClustersClassBased() throws IOException {
        addChunk("p", "c1", 0, "jta jta jta pool");
        addChunk("p", "c2", 0, "jta connection pool");
        addChunk("p", "c3", 0, "hikari hikari hikari jta");

        Map<String, List<String>> clusterToChunkIds = new LinkedHashMap<>();
        clusterToChunkIds.put("s1", List.of("c1", "c2"));
        clusterToChunkIds.put("s2", List.of("c3"));

        Map<String, List<String>> signatures = signer.signClusters(store, "p", clusterToChunkIds);

        assertThat(signatures).containsOnlyKeys("s1", "s2");
        assertThat(signatures.get("s1").get(0)).isEqualTo("jta");
        assertThat(signatures.get("s2").get(0)).isEqualTo("hikari");
    }

    @Test
    void signClustersWithoutMembersYieldsEmptySignature() throws IOException {
        addChunk("p", "c1", 0, "jta jta jta");

        Map<String, List<String>> signatures = signer.signClusters(
                store, "p", Map.of("empty", List.of(), "missing", List.of("unknown")));

        assertThat(signatures).containsEntry("empty", List.of());
        assertThat(signatures).containsEntry("missing", List.of());
    }

    @Test
    void emptyIndex() throws IOException {
        assertThat(signer.signChunks(store, "p")).isEmpty();
        assertThat(signer.signClusters(store, "p", Map.of("s1", List.of("c1"))))
                .containsEntry("s1", List.of());
    }

    @Test
    void projectIsolation() throws IOException {
        addChunk("p", "c1", 0, "jta jta jta");

        assertThat(signer.signChunks(store, "missing")).isEmpty();
    }

    private void addChunk(String project, String id, int part, String content) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, Integer.toString(part));
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from(content, metadata));
    }
}
