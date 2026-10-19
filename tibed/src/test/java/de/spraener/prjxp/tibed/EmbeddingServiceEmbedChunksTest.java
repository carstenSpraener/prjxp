package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link EmbeddingService#embedChunks} — the public entry point used by the hub's
 * document-embedding runs. All 8 constructor dependencies are mocked; the real dedup logic
 * ({@code needsEmbedding} → {@code storeIdChecker.needsImport}) is exercised.
 */
class EmbeddingServiceEmbedChunksTest {

    private PxLogService logService;
    private ObjectMapper objMapper;
    private EmbeddingExecutor embedder;
    private EmbeddingStoreSupplier storeSupplier;
    private PrjXPJsonStreamProvider streamProvider;
    private TransferPasswordResolver transferPasswordResolver;
    private StoreIdChecker storeIdChecker;
    private PrjXPConfig cfg;

    private EmbeddingService uut;
    private EmbeddingStore<TextSegment> store;

    @BeforeEach
    void setUp() {
        logService = mock(PxLogService.class);
        objMapper = new ObjectMapper();
        embedder = mock(EmbeddingExecutor.class);
        storeSupplier = mock(EmbeddingStoreSupplier.class);
        streamProvider = mock(PrjXPJsonStreamProvider.class);
        transferPasswordResolver = mock(TransferPasswordResolver.class);
        storeIdChecker = mock(StoreIdChecker.class);
        cfg = new PrjXPConfig();
        uut = new EmbeddingService(logService, objMapper, embedder, storeSupplier, streamProvider, transferPasswordResolver, storeIdChecker, cfg);
        store = mock(EmbeddingStore.class);
    }

    private PxChunk chunk(String id) {
        return PxChunk.create(c -> c.setId(id), c -> c.setContent("content of " + id));
    }

    @Test
    void stampsProjectAndDelegatesToEmbedder() {
        when(storeIdChecker.needsImport(any(), anyString(), anyString())).thenReturn(true);
        PxChunk c1 = chunk("c1");
        PxChunk c2 = chunk("c2");

        uut.embedChunks(store, "p1", List.of(c1, c2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embedder).execute(eq(store), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(c -> assertThat(c.getProject()).isEqualTo("p1"));
    }

    @Test
    void dedupFiltersAlreadyImported() {
        // c2 is already in the store -> needsImport false -> filtered out before the embedder
        when(storeIdChecker.needsImport(any(), anyString(), anyString())).thenAnswer(inv ->
                !"c2".equals(inv.getArgument(1)));
        PxChunk c1 = chunk("c1");
        PxChunk c2 = chunk("c2");

        uut.embedChunks(store, "p1", List.of(c1, c2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embedder).execute(eq(store), captor.capture());
        assertThat(captor.getValue()).extracting(PxChunk::getId).containsExactly("c1");
    }
}
