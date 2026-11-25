package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import de.spraener.prjxp.tibed.signature.SignatureBatch;
import de.spraener.prjxp.tibed.semantic.SemanticGraphBatch;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class EmbeddingServiceSemanticTest {

    @TempDir
    Path tempDir;

    private PxLogService logService;
    private ObjectMapper objectMapper;
    private EmbeddingExecutor embedder;
    private EmbeddingStoreSupplier storeSupplier;
    private PrjXPJsonStreamProvider streamProvider;
    private TransferPasswordResolver passwordResolver;
    private StoreIdChecker storeIdChecker;
    private SemanticGraphBatch semanticGraphBatch;
    private SignatureBatch signatureBatch;
    private PrjXPConfig config;
    private LuceneEmbeddingStore luceneStore;
    private ProjectDefinition project;
    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        logService = mock(PxLogService.class);
        objectMapper = new ObjectMapper();
        embedder = mock(EmbeddingExecutor.class);
        storeSupplier = mock(EmbeddingStoreSupplier.class);
        streamProvider = mock(PrjXPJsonStreamProvider.class);
        passwordResolver = mock(TransferPasswordResolver.class);
        storeIdChecker = mock(StoreIdChecker.class);
        semanticGraphBatch = mock(SemanticGraphBatch.class);
        signatureBatch = mock(SignatureBatch.class);
        config = new PrjXPConfig();
        project = new ProjectDefinition();
        project.setName("p");
        project.setRootDir(tempDir.toString());
        project.setJsonlFile(tempDir.resolve("empty.jsonl").toString());
        luceneStore = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        service = new EmbeddingService(logService, objectMapper, embedder, storeSupplier,
                streamProvider, passwordResolver, storeIdChecker, config, semanticGraphBatch,
                mock(AstNeighboursLoader.class), signatureBatch);
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();
    }

    @Test
    void runsSemanticBatchAfterSuccessfulIngestionWhenEnabled() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());

        service.executeForProject(project, luceneStore);

        verify(semanticGraphBatch).run("p", luceneStore);
    }

    @Test
    void skipsSemanticBatchWhenDisabledOrStoreIsNotLucene() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());
        config.getSemanticGraph().setEnabled(false);

        service.executeForProject(project, luceneStore);
        verifyNoInteractions(semanticGraphBatch);

        config.getSemanticGraph().setEnabled(true);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> genericStore = mock(EmbeddingStore.class);
        service.executeForProject(project, genericStore);
        verifyNoInteractions(semanticGraphBatch);
    }

    @Test
    void skipsSemanticBatchAfterJsonlReadFailure() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenThrow(new IOException("stream failed"));

        service.executeForProject(project, luceneStore);

        verifyNoInteractions(semanticGraphBatch);
    }

    @Test
    void skipsSemanticBatchAfterEmbeddingBatchFailure() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.of("{\"id\":\"chunk-1\",\"content\":\"text\"}"));
        when(storeIdChecker.needsImport(any(), eq("chunk-1"), eq("p"))).thenReturn(true);
        doThrow(new IllegalStateException("embedding failed"))
                .when(embedder).execute(eq(luceneStore), anyList());

        service.executeForProject(project, luceneStore);

        verifyNoInteractions(semanticGraphBatch);
    }

    @Test
    void surfacesSemanticBatchFailure() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());
        doThrow(new IOException("sidecar failed")).when(semanticGraphBatch).run("p", luceneStore);

        assertThatThrownBy(() -> service.executeForProject(project, luceneStore))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Semantic-graph step failed");
    }

    @Test
    void runsSignatureBatchEvenWhenSemanticGraphIsDisabled() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());
        config.getSemanticGraph().setEnabled(false);

        service.executeForProject(project, luceneStore);

        verify(signatureBatch).run("p", luceneStore);
        verifyNoInteractions(semanticGraphBatch);
    }

    @Test
    void skipsSignatureBatchWhenDisabledOrAfterFailure() throws Exception {
        config.getSignature().setEnabled(false);
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());

        service.executeForProject(project, luceneStore);
        verifyNoInteractions(signatureBatch);

        config.getSignature().setEnabled(true);
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenThrow(new IOException("stream failed"));
        service.executeForProject(project, luceneStore);
        verifyNoInteractions(signatureBatch);
    }

    @Test
    void surfacesSignatureBatchFailure() throws Exception {
        when(streamProvider.getTransferJsonlStream(eq(project.resolvedJsonlFile()), isNull()))
                .thenReturn(Stream.empty());
        doThrow(new IOException("signature failed")).when(signatureBatch).run("p", luceneStore);

        assertThatThrownBy(() -> service.executeForProject(project, luceneStore))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Signature step failed");
    }

    @Test
    void parsesSemanticGraphOptOutAndCalibrationFlags() {
        CliArgsParser parser = new CliArgsParser(
                logService, config, new MockEnvironment());

        parser.parseArgs(new CliArgsParsingEvent(new String[]{"--no-semantic-graph"}, config));
        assertThat(config.getSemanticGraph().isEnabled()).isFalse();

        config.getSemanticGraph().setCalibrate(false);
        parser.parseArgs(new CliArgsParsingEvent(new String[]{"--calibrate"}, config));
        assertThat(config.getSemanticGraph().isCalibrate()).isTrue();
    }
}
