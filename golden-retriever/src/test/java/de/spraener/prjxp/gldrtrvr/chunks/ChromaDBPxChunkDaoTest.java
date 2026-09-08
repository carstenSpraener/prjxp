package de.spraener.prjxp.gldrtrvr.chunks;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChromaDBPxChunkDaoTest {

    @Mock
    EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    EmbeddingModel embeddingModel;

    ChromaDBPxChunkDao dao;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        PrjXPEmbeddingStoreReference storeReference = new PrjXPEmbeddingStoreReference();
        storeReference.setProjectName("test-project");
        storeReference.setCollectionName("test-collection");

        dao = new ChromaDBPxChunkDao(embeddingStore, embeddingModel, storeReference);

        when(embeddingModel.embed(any(String.class))).thenReturn(
                Response.from(Embedding.from(new float[]{0.5f, 0.3f, 0.2f}))
        );
    }

    private TextSegment segment(String id, String content) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        return TextSegment.from(content, metadata);
    }

    @Test
    void searchVectorMapsMatchesToScoredChunks() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(0.9, "id-1", null, segment("chunk-1", "First content")),
                        new EmbeddingMatch<>(0.7, "id-2", null, segment("chunk-2", "Second content")))));

        List<ScoredChunk> result = dao.searchVector("How does it work?", Map.of(), 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunk().getId()).isEqualTo("chunk-1");
        assertThat(result.get(0).score()).isEqualTo(0.9);
        assertThat(result.get(1).chunk().getId()).isEqualTo("chunk-2");
        assertThat(result.get(1).score()).isEqualTo(0.7);
    }

    @Test
    void searchVectorAppliesMimeFilter() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        dao.searchVector("q", Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code"), 5);

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(captor.capture());

        assertThat(captor.getValue().maxResults()).isEqualTo(5);
        IsEqualTo filter = (IsEqualTo) captor.getValue().filter();
        assertThat(filter.key()).isEqualTo(PxChunk.PXCHUNK_MIME_TYPE);
        assertThat(filter.comparisonValue()).isEqualTo("text/x-java-code");
    }

    @Test
    void searchVectorPrefixesCustomMetadataKeys() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        dao.searchVector("q", Map.of("symbol_fqn", "com.example.Foo"), 5);

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(captor.capture());

        IsEqualTo filter = (IsEqualTo) captor.getValue().filter();
        assertThat(filter.key()).isEqualTo(PxChunk.PXCHUNK_METADATA + ".symbol_fqn");
    }

    @Test
    void searchVectorBlankQueryReturnsEmpty() {
        dao.searchVector("   ", Map.of(), 5);

        verifyNoInteractions(embeddingStore);
        verifyNoInteractions(embeddingModel);
    }
}
