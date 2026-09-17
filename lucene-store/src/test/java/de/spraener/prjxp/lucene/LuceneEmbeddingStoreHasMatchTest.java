package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneEmbeddingStoreHasMatchTest {

    @TempDir
    Path tempDir;

    @Test
    void hasMatchUsesFilterWithoutVectorSearch() {
        LuceneEmbeddingStore store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);

        Metadata metadata = new Metadata();
        metadata.put("pxchunk_id", "chunk-42");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Chunk", metadata));

        assertThat(store.hasMatch(new IsEqualTo("pxchunk_id", "chunk-42"))).isTrue();
        assertThat(store.hasMatch(new IsEqualTo("pxchunk_id", "chunk-404"))).isFalse();
    }
}
