package de.spraener.prjxp.tibed.embedder;

import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LangChain4JEmbedderImplTest {

    @TempDir
    Path tempDir;

    /** Records embedAll calls; every segment gets the same deterministic vector. */
    private static class RecordingEmbeddingModel implements EmbeddingModel {
        final List<List<TextSegment>> calls = new ArrayList<>();

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls.add(segments);
            return new Response<>(segments.stream()
                    .map(s -> Embedding.from(new float[]{1f, 0f, 0f}))
                    .toList());
        }

        @Override
        public int dimension() {
            return 3;
        }
    }

    private static PxChunk chunk(String id, String content, boolean fulltext) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFile("src/A.java");
            c.setContent(content);
            if (fulltext) {
                c.getMetadata().put(PxChunk.PXCHUNK_TYPE, PxChunk.FULLTEXT_MARKER);
            }
        });
    }

    private LangChain4JEmbedderImpl uut(RecordingEmbeddingModel model) {
        return new LangChain4JEmbedderImpl(model, Mockito.mock(EmbeddingStoreSupplier.class));
    }

    @Test
    void fulltextPartsGetPlaceholderVectorNormalPartsGetRealEmbedding() {
        LuceneEmbeddingStore store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        uut.execute(store, List.of(
                chunk("chunk-normal", "normal content", false),
                chunk("chunk-ft", "fulltext content", true)));

        // embedAll is called ONLY with the normal segment (no API cost for fulltext)
        assertThat(model.calls).hasSize(1);
        assertThat(model.calls.get(0)).hasSize(1);
        assertThat(model.calls.get(0).get(0).text()).isEqualTo("normal content");

        // both docs are stored; querying with the placeholder itself (all ones) proves
        // the fulltext doc carries the placeholder vector (score 1.0), while the normal
        // doc (fake vector {1,0,0}) scores 0.5 + 0.5 * cos = 0.5 + 0.5/sqrt(3)
        var result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 1f, 1f}))
                .maxResults(10)
                .build());
        Map<String, Double> scores = result.matches().stream()
                .collect(Collectors.toMap(
                        m -> m.embedded().metadata().getString(PxChunk.PXCHUNK_ID),
                        EmbeddingMatch::score));
        assertThat(scores).hasSize(2);
        assertThat(scores.get("chunk-ft")).isCloseTo(1.0, within(0.01));
        assertThat(scores.get("chunk-normal")).isCloseTo(0.5 + 0.5 / Math.sqrt(3), within(0.01));
    }

    @Test
    void allFulltextBatchSkipsEmbeddingModel() {
        LuceneEmbeddingStore store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        uut.execute(store, List.of(chunk("chunk-ft", "fulltext content", true)));

        assertThat(model.calls).isEmpty();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void emptyBatchIsSkipped() {
        LuceneEmbeddingStore store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        uut.execute(store, List.of());

        assertThat(model.calls).isEmpty();
        assertThat(store.count()).isZero();
    }

    @Test
    void fulltextMarkerIsStoredInDocument() {
        LuceneEmbeddingStore store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        uut.execute(store, List.of(
                chunk("chunk-normal", "normal content", false),
                chunk("chunk-ft", "fulltext content", true)));

        var result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f}))
                .filter(new IsEqualTo(PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, PxChunk.FULLTEXT_MARKER))
                .maxResults(10)
                .build());

        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().metadata()
                .getString(PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.FULLTEXT_MARKER);
    }

    @Test
    void embedReturnsRecordsWithPrefixInVector() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-prefixed");
            c.setFile("src/A.java");
            c.setEmbeddingPrefix("com.example Foo");
            c.setContent("normal content");
        });

        List<EmbeddedChunkRecord> records = uut.embed(List.of(chunk));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).id()).isEqualTo("chunk-prefixed");
        assertThat(records.get(0).embeddingPrefix()).isEqualTo("com.example Foo");
        assertThat(records.get(0).vector()).containsExactly(1f, 0f, 0f);
        // the model received the prefixed content
        assertThat(model.calls).hasSize(1);
        assertThat(model.calls.get(0).get(0).text()).contains("com.example Foo").contains("normal content");
    }

    @Test
    void embedFulltextGetsPlaceholderVector() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        List<EmbeddedChunkRecord> records = uut.embed(List.of(chunk("chunk-ft", "fulltext content", true)));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).id()).isEqualTo("chunk-ft");
        assertThat(records.get(0).vector()).containsExactly(1f, 1f, 1f);   // all-ones placeholder (dim 3)
        assertThat(model.calls).isEmpty();
    }

    @Test
    void embedEmptyListReturnsEmpty() {
        RecordingEmbeddingModel model = new RecordingEmbeddingModel();
        LangChain4JEmbedderImpl uut = uut(model);

        assertThat(uut.embed(List.of())).isEmpty();
        assertThat(model.calls).isEmpty();
    }
}
