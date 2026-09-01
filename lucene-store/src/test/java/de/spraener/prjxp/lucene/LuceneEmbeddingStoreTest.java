package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneEmbeddingStoreTest {

    @TempDir
    Path tempDir;

    LuceneEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
    }

    @Test
    void addSingleEmbeddingReturnsId() {
        Embedding e = Embedding.from(new float[]{1f, 0f, 0f});
        String id = store.add(e);
        assertThat(id).isNotBlank();
    }

    @Test
    void addWithIdAndEmbedding() {
        Embedding e = Embedding.from(new float[]{1f, 0f, 0f});
        store.add("my-id", e);
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void addWithEmbeddingAndTextSegment() {
        Embedding e = Embedding.from(new float[]{1f, 0f, 0f});
        Metadata metadata = new Metadata();
        metadata.put("pxchunk_id", "chunk-1");
        metadata.put("source", "test.txt");
        TextSegment segment = TextSegment.from("Hello world", metadata);

        String id = store.add(e, segment);
        assertThat(id).isNotBlank();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void addAllEmbeddings() {
        List<Embedding> embeddings = Arrays.asList(
                Embedding.from(new float[]{1f, 0f, 0f}),
                Embedding.from(new float[]{0f, 1f, 0f})
        );
        List<String> ids = store.addAll(embeddings);
        assertThat(ids).hasSize(2);
        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void addAllEmbeddingsWithTextSegments() {
        List<Embedding> embeddings = Arrays.asList(
                Embedding.from(new float[]{1f, 0f, 0f}),
                Embedding.from(new float[]{0f, 1f, 0f})
        );

        Metadata meta1 = new Metadata();
        meta1.put("pxchunk_id", "chunk-1");
        TextSegment seg1 = TextSegment.from("Content 1", meta1);

        Metadata meta2 = new Metadata();
        meta2.put("pxchunk_id", "chunk-2");
        TextSegment seg2 = TextSegment.from("Content 2", meta2);

        List<String> ids = store.addAll(embeddings, Arrays.asList(seg1, seg2));
        assertThat(ids).hasSize(2);
    }

    @Test
    void addAllSizeMismatchThrows() {
        assertThatThrownBy(() -> store.addAll(
                Collections.singletonList(Embedding.from(new float[]{1f, 0f, 0f})),
                new ArrayList<>()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchReturnsNearestNeighbors() {
        addWithContent("a", new float[]{1f, 0f, 0f});
        addWithContent("b", new float[]{0f, 1f, 0f});
        addWithContent("c", new float[]{0.9f, 0.1f, 0f});

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .maxResults(2)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        assertThat(result.matches()).hasSize(2);
    }

    @Test
    void searchRespectsMaxResults() {
        for (int i = 0; i < 10; i++) {
            addWithContent("doc-" + i, new float[]{1f, 0f, 0f});
        }

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .maxResults(3)
                .build();

        assertThat(store.search(request).matches()).hasSize(3);
    }

    @Test
    void searchRespectsMinScore() {
        addWithContent("close", new float[]{1f, 0f, 0f});
        addWithContent("far", new float[]{0f, 1f, 0f});

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .maxResults(10)
                .minScore(0.9)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            assertThat(match.score()).isGreaterThanOrEqualTo(0.9);
        }
    }

    @Test
    void searchWithMetadataFilter() {
        Metadata meta1 = new Metadata();
        meta1.put("source", "file-a.txt");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Content A", meta1));

        Metadata meta2 = new Metadata();
        meta2.put("source", "file-b.txt");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Content B", meta2));

        Filter filter = new IsEqualTo("source", "file-a.txt");
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .filter(filter)
                .maxResults(10)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("Content A");
    }

    @Test
    void metadataRoundtrip() {
        Metadata original = new Metadata();
        original.put("pxchunk_id", "test-123");
        original.put("pxchunk_file", "src/Main.java");
        original.put("custom_key", "custom_value");

        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("My content", original));

        Filter filter = new IsEqualTo("pxchunk_id", "test-123");
        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .filter(filter)
                .maxResults(1)
                .build());

        TextSegment found = result.matches().get(0).embedded();
        assertThat(found.text()).isEqualTo("My content");
        Map<String, Object> metaMap = found.metadata().toMap();
        assertThat(metaMap).containsEntry("pxchunk_file", "src/Main.java");
        assertThat(metaMap).containsEntry("custom_key", "custom_value");
    }

    @Test
    void emptyIndexSearchReturnsEmpty() {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .maxResults(5)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void indexPersistsAfterReopen() {
        Metadata meta = new Metadata();
        meta.put("key", "value");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("persisted", meta));
        store.close();

        LuceneEmbeddingStore reopened = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        assertThat(reopened.count()).isEqualTo(1);

        EmbeddingSearchResult<TextSegment> result = reopened.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .maxResults(1)
                .build());

        assertThat(result.matches().get(0).embedded().text()).isEqualTo("persisted");
    }

    @Test
    void removeAllWithFilter() {
        Metadata meta1 = new Metadata();
        meta1.put("type", "A");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("A", meta1));

        Metadata meta2 = new Metadata();
        meta2.put("type", "B");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("B", meta2));

        assertThat(store.count()).isEqualTo(2);

        store.removeAll(new IsEqualTo("type", "A"));
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void dimensionMismatchThrows() {
        assertThatThrownBy(() -> store.add(Embedding.from(new float[]{1f, 0f})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension mismatch");
    }

    @Test
    void concurrentAccess() throws InterruptedException {
        store = new LuceneEmbeddingStore(tempDir.resolve("index2"), 3);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                Metadata m = new Metadata();
                m.put("idx", String.valueOf(i));
                store.add(Embedding.from(new float[]{1f, 0f, 0f}),
                        TextSegment.from("content-" + i, m));
            }
        });

        Thread reader = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                        .maxResults(5)
                        .build());
            }
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        assertThat(store.count()).isEqualTo(50);
    }

    private void addWithContent(String id, float[] vector) {
        Metadata m = new Metadata();
        m.put("_name", id);
        store.add(Embedding.from(vector), TextSegment.from("content-for-" + id, m));
    }
}
