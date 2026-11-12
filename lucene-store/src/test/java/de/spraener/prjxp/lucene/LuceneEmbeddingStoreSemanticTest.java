package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneEmbeddingStoreSemanticTest {

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
    void readAllVectorsReturnsLiveVectorsAndMetadataAndSupportsProjectFilter() throws IOException {
        addChunk("p", "one", 0, new float[]{1f, 0f, 0f}, "first");
        addChunk("p", "two", 0, new float[]{0f, 1f, 0f}, "second");
        addChunk("q", "three", 0, new float[]{0f, 0f, 1f}, "third");

        List<VectorChunk> all = store.readAllVectors(null);
        assertThat(all).hasSize(3);
        VectorChunk first = all.stream().filter(vc -> vc.id().equals("one")).findFirst().orElseThrow();
        assertThat(first.nodeId()).isEqualTo(VectorChunk.nodeIdFor("p", "one", 0));
        assertThat(first.project()).isEqualTo("p");
        assertThat(first.part()).isZero();
        assertThat(first.vector()).containsExactly(1f, 0f, 0f);
        assertThat(first.content()).isEqualTo("content-one");
        assertThat(first.metadata()).containsEntry(PxChunk.PXCHUNK_FILE, "first.md");

        List<VectorChunk> projectP = store.readAllVectors(
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p"));
        assertThat(projectP).extracting(VectorChunk::id).containsExactlyInAnyOrder("one", "two");

        assertThat(store.readAllVectors(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "missing"))).isEmpty();
    }

    @Test
    void readAllVectorsSkipsDeletedDocuments() throws IOException {
        addChunk("p", "live", 0, new float[]{1f, 0f, 0f}, "live");
        addChunk("p", "deleted", 0, new float[]{0f, 1f, 0f}, "deleted");

        store.removeAll(new IsEqualTo(PxChunk.PXCHUNK_ID, "deleted"));

        assertThat(store.readAllVectors(null)).extracting(VectorChunk::id).containsExactly("live");
    }

    @Test
    void readChunkByIdReadsExactNodeAndScopesItToProject() throws IOException {
        addChunk("p", "same-id", 0, new float[]{1f, 0f, 0f}, "p");
        addChunk("q", "same-id", 0, new float[]{0f, 1f, 0f}, "q");
        String pNodeId = VectorChunk.nodeIdFor("p", "same-id", 0);

        Optional<VectorChunk> found = store.readChunkById(pNodeId, "p");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().project()).isEqualTo("p");
        assertThat(found.orElseThrow().vector()).containsExactly(1f, 0f, 0f);
        assertThat(store.readChunkById(pNodeId, "q")).isEmpty();
        assertThat(store.readChunkById("not-a-node", "p")).isEmpty();
    }

    @Test
    void searchKnnRanksBySimilarityAndAppliesScoreAndProjectFilter() throws IOException {
        addChunk("p", "nearest", 0, new float[]{1f, 0f, 0f}, "nearest");
        addChunk("p", "orthogonal", 0, new float[]{0f, 1f, 0f}, "orthogonal");
        addChunk("q", "foreign-nearest", 0, new float[]{1f, 0f, 0f}, "foreign");

        Embedding query = Embedding.from(new float[]{1f, 0f, 0f});
        List<EmbeddingMatch<TextSegment>> allProjects =
                store.searchKnn(query, 10, 0, null);
        assertThat(allProjects).hasSize(3);
        assertThat(List.of(
                VectorChunk.nodeIdFor("p", "nearest", 0),
                VectorChunk.nodeIdFor("q", "foreign-nearest", 0)))
                .contains(allProjects.get(0).embeddingId());

        List<EmbeddingMatch<TextSegment>> scoped =
                store.searchKnn(query, 10, 0.85, new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "p"));
        assertThat(scoped).singleElement().satisfies(match -> {
            assertThat(match.embeddingId()).isEqualTo(VectorChunk.nodeIdFor("p", "nearest", 0));
            assertThat(match.score()).isGreaterThanOrEqualTo(0.85);
        });
    }

    @Test
    void searchKnnReturnsEmptyForEmptyIndex() throws IOException {
        assertThat(store.searchKnn(Embedding.from(new float[]{1f, 0f, 0f}), 5, 0, null)).isEmpty();
    }

    private void addChunk(String project, String id, int part, float[] vector, String file) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, Integer.toString(part));
        metadata.put(PxChunk.PXCHUNK_FILE, file + ".md");
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown");
        metadata.put(PxChunk.PXCHUNK_METADATA + "." + "semantic_node_id",
                VectorChunk.nodeIdFor(project, id, part));
        store.add(Embedding.from(vector), TextSegment.from("content-" + id, metadata));
    }
}
