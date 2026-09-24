package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 01 (DockerHub): {@link EmbeddingService#executeForProject(ProjectDefinition, EmbeddingStore)}
 * must run the embedding pipeline for an explicitly given project + store (hub in-process use).
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingServiceForProjectTest {

    @Mock
    PxLogService logService;

    @Mock
    EmbeddingExecutor embedder;

    @Mock
    EmbeddingStoreSupplier storeSupplier;

    @Mock
    PrjXPJsonStreamProvider streamProvider;

    @Mock
    StoreIdChecker storeIdChecker;

    @Mock
    PrjXPConfig cfg;

    private final ObjectMapper objMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new EmbeddingService(logService, objMapper, embedder, storeSupplier, streamProvider, storeIdChecker, cfg);
    }

    private ProjectDefinition projectWithJsonl(String name) throws Exception {
        Path jsonl = tempDir.resolve(name + ".jsonl");
        Files.writeString(jsonl, "{\"id\":\"chunk-1\",\"content\":\"int x = 42;\"}");
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName(name);
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile(jsonl.toString());
        return pd;
    }

    @SuppressWarnings("unchecked")
    private EmbeddingStore<TextSegment> stubStreamAndImport(ProjectDefinition pd) throws Exception {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(streamProvider.getJsonlStream(pd.getJsonlFile()))
                .thenReturn(java.util.stream.Stream.of("{\"id\":\"chunk-1\",\"content\":\"int x = 42;\"}"));
        when(storeIdChecker.needsImport(eq(store), eq("chunk-1"), eq(pd.getName()))).thenReturn(true);
        return store;
    }

    @Test
    void executeForProjectStampsChunkWithGivenProjectAndChecksImportAgainstIt() throws Exception {
        ProjectDefinition pd = projectWithJsonl("projA");
        EmbeddingStore<TextSegment> store = stubStreamAndImport(pd);

        service.executeForProject(pd, store);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embedder).execute(eq(store), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getId()).isEqualTo("chunk-1");
        assertThat(captor.getValue().get(0).getProject()).isEqualTo("projA");
        verify(storeIdChecker).needsImport(eq(store), eq("chunk-1"), eq("projA"));
    }

    @Test
    void executeResolvesActiveProjectAndUsesSupplierStore() throws Exception {
        ProjectDefinition pd = projectWithJsonl("projA");
        when(cfg.getActiveProject()).thenReturn(Optional.of(pd));
        EmbeddingStore<TextSegment> store = stubStreamAndImport(pd);
        when(storeSupplier.getStore("projA")).thenReturn(store);

        service.execute();

        verify(embedder).execute(eq(store), anyList());
    }
}
