package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneEmbeddingStoreUpsertTest {

    private static final String CLUSTER_FIELD = "pxchunk_metadata.semantic_cluster_id";

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void upsertAllCreatesANewDocument() throws IOException {
        VectorChunk update = update("p", "new", 0, new float[]{1f, 0f, 0f},
                "new content", Map.of(PxChunk.PXCHUNK_FILE, "new.md"));

        store.upsertAll(List.of(update));

        assertThat(store.count()).isEqualTo(1);
        VectorChunk found = store.searchByMetadata(PxChunk.PXCHUNK_FILE, "new.md", null, 10)
                .get(0);
        assertThat(found.nodeId()).isEqualTo(update.nodeId());
        assertThat(found.content()).isEqualTo("new content");
        assertThat(found.project()).isEqualTo("p");
    }

    @Test
    void upsertAllPreservesExistingFieldsAndVector() throws IOException {
        addChunk("p", "existing", 0, new float[]{1f, 0f, 0f}, "old.md", "old content");
        VectorChunk update = update("p", "existing", 0, null, null,
                Map.of(CLUSTER_FIELD, "s3"));

        store.upsertAll(List.of(update));

        VectorChunk found = store.readAllVectors(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p")).get(0);
        assertThat(found.vector()).containsExactly(1f, 0f, 0f);
        assertThat(found.content()).isEqualTo("old content");
        assertThat(found.metadata()).containsEntry(PxChunk.PXCHUNK_FILE, "old.md")
                .containsEntry(CLUSTER_FIELD, "s3");
    }

    @Test
    void upsertAllOverwritesProvidedFields() throws IOException {
        addChunk("p", "change", 0, new float[]{1f, 0f, 0f}, "old.md", "old content");

        store.upsertAll(List.of(update("p", "change", 0, new float[]{0f, 1f, 0f},
                "new content", Map.of(PxChunk.PXCHUNK_FILE, "new.md"))));

        VectorChunk found = store.readAllVectors(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p")).get(0);
        assertThat(found.content()).isEqualTo("new content");
        assertThat(found.vector()).containsExactly(0f, 1f, 0f);
        assertThat(store.searchByMetadata(PxChunk.PXCHUNK_FILE, "old.md", null, 10)).isEmpty();
    }

    @Test
    void upsertAllScopesIdentityByProjectAndPart() throws IOException {
        addChunk("p", "same", 0, new float[]{1f, 0f, 0f}, "p0.md", "p part 0");
        addChunk("p", "same", 1, new float[]{0f, 1f, 0f}, "p1.md", "p part 1");
        addChunk("q", "same", 0, new float[]{0f, 0f, 1f}, "q0.md", "q part 0");

        store.upsertAll(List.of(update("p", "same", 0, new float[]{-1f, 0f, 0f},
                "updated", Map.of(PxChunk.PXCHUNK_FILE, "updated.md"))));

        assertThat(store.count()).isEqualTo(3);
        List<VectorChunk> chunks = store.readAllVectors(null);
        assertThat(chunks).hasSize(3);
        assertThat(find(chunks, "p", "same", 0).content()).isEqualTo("updated");
        assertThat(find(chunks, "p", "same", 1).content()).isEqualTo("p part 1");
        assertThat(find(chunks, "q", "same", 0).content()).isEqualTo("q part 0");
    }

    @Test
    void upsertAllRemovesMetadataWhenPatchValueIsNull() throws IOException {
        addChunk("p", "clustered", 0, new float[]{1f, 0f, 0f}, "clustered.md", "content");
        store.upsertAll(List.of(update("p", "clustered", 0, null, null,
                Map.of(CLUSTER_FIELD, "s1"))));
        Map<String, String> removal = new HashMap<>();
        removal.put(CLUSTER_FIELD, null);

        store.upsertAll(List.of(update("p", "clustered", 0, null, null, removal)));

        assertThat(store.searchByMetadata(CLUSTER_FIELD, "s1", null, 10)).isEmpty();
        assertThat(find(store.readAllVectors(null), "p", "clustered", 0).metadata())
                .doesNotContainKey(CLUSTER_FIELD);
    }

    @Test
    void upsertAllEmptyIsANoOp() {
        addChunk("p", "existing", 0, new float[]{1f, 0f, 0f}, "file.md", "content");

        store.upsertAll(List.of());

        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void upsertAllRejectsMissingVectorForNewIdentity() {
        assertThatThrownBy(() -> store.upsertAll(List.of(
                update("p", "no-vector", 0, null, null, Map.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.count()).isZero();
    }

    @Test
    void upsertAllRejectsDuplicateNodeIdsBeforeWriting() {
        VectorChunk update = update("p", "duplicate", 0, new float[]{1f, 0f, 0f},
                "content", Map.of());

        assertThatThrownBy(() -> store.upsertAll(List.of(update, update)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.count()).isZero();
    }

    @Test
    void searchByMetadataScopesByProjectAndRespectsMax() {
        addClustered("p", "p1", "s1");
        addClustered("p", "p2", "s1");
        addClustered("q", "q1", "s1");
        addChunk("p", "unclustered", 0, new float[]{0f, 0f, 1f}, "plain.md", "plain");

        assertThat(store.searchByMetadata(CLUSTER_FIELD, "s1",
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p"), 10))
                .extracting(VectorChunk::id).containsExactlyInAnyOrder("p1", "p2");
        assertThat(store.searchByMetadata(CLUSTER_FIELD, "s1", null, 1)).hasSize(1);
        assertThat(store.searchByMetadata(CLUSTER_FIELD, "s1", null, 0)).isEmpty();
    }

    @Test
    void countByMetadataGroupsAllValuesAndScopesByProject() {
        addClustered("p", "p1", "s1");
        addClustered("p", "p2", "s1");
        addClustered("p", "p3", "s2");
        addClustered("q", "q1", "s1");
        addChunk("p", "unclustered", 0, new float[]{0f, 0f, 1f}, "plain.md", "plain");

        assertThat(store.countByMetadata(CLUSTER_FIELD, null))
                .containsEntry("s1", 3L).containsEntry("s2", 1L);
        assertThat(store.countByMetadata(CLUSTER_FIELD,
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p")))
                .containsEntry("s1", 2L).containsEntry("s2", 1L);
        assertThat(store.countByMetadata(CLUSTER_FIELD,
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "missing"))).isEmpty();
    }

    @Test
    void upsertIsImmediatelyVisibleToMetadataQueries() {
        store.upsertAll(List.of(update("p", "visible", 0, new float[]{1f, 0f, 0f},
                "visible", Map.of(CLUSTER_FIELD, "s9"))));

        assertThat(store.searchByMetadata(CLUSTER_FIELD, "s9", null, 10))
                .extracting(VectorChunk::id).containsExactly("visible");
    }

    private VectorChunk update(String project, String id, int part, float[] vector,
                               String content, Map<String, String> metadata) {
        return new VectorChunk(VectorChunk.nodeIdFor(project, id, part),
                id, project, part, vector, content, metadata);
    }

    private void addClustered(String project, String id, String clusterId) {
        addChunk(project, id, 0, new float[]{1f, 0f, 0f}, id + ".md", id);
        store.upsertAll(List.of(update(project, id, 0, null, null,
                Map.of(CLUSTER_FIELD, clusterId))));
    }

    private void addChunk(String project, String id, int part, float[] vector,
                          String file, String content) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, Integer.toString(part));
        metadata.put(PxChunk.PXCHUNK_FILE, file);
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown");
        metadata.put(PxChunk.PXCHUNK_METADATA + ".semantic_node_id",
                VectorChunk.nodeIdFor(project, id, part));
        store.add(Embedding.from(vector), TextSegment.from(content, metadata));
    }

    private VectorChunk find(List<VectorChunk> chunks, String project, String id, int part) {
        return chunks.stream()
                .filter(vc -> vc.project().equals(project) && vc.id().equals(id) && vc.part() == part)
                .findFirst()
                .orElseThrow();
    }
}
