package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.chuno.ChunkerFactory;
import de.spraener.prjxp.chuno.PxChunker;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransferPipelineE2ETest {
    @TempDir
    Path tempDir;

    private static final int DIMENSION = 8;
    private static final String PASSWORD_ENV_KEY = "PRJXP_TRANSFER_PASSWORD";

    // ---------- Pipeline-Schritte (echter Produktionscode) ----------

    private void runChunkStep(PrjXPConfig cfg, TransferPasswordResolver resolver, PrintStream capturedOut) throws Exception {
        ChunkerFactory factory = Mockito.mock(ChunkerFactory.class);
        Mockito.when(factory.createChunker(Mockito.any(File.class)))
                .thenAnswer(invocation -> Stream.of(new FileContentChunker()));
        Mockito.when(factory.listPostWalkChunker())
                .thenAnswer(invocation -> Stream.empty());

        VetoRegistry vetoRegistry = Mockito.mock(VetoRegistry.class);
        Mockito.when(vetoRegistry.shouldVeto(Mockito.any(Path.class))).thenReturn(false);

        ChunkProcess chunkProcess = new ChunkProcess(
                new PxLogService(),
                factory,
                Mockito.mock(ApplicationEventPublisher.class),
                vetoRegistry,
                cfg,
                resolver
        );

        PrintStream original = System.out;
        System.setOut(capturedOut);
        try {
            chunkProcess.execute();
        } finally {
            System.setOut(original);
        }
    }

    private void runExportStep(PrjXPConfig cfg, TransferPasswordResolver resolver) {
        new EmbeddingExportService(
                new PxLogService(),
                new ObjectMapper(),
                new FakeEmbeddingModel(DIMENSION),
                new PrjXPJsonStreamProvider(cfg),
                resolver,
                cfg
        ).execute();
    }

    private PxLogService runImportStep(PrjXPConfig cfg, TransferPasswordResolver resolver, FakeEmbeddingStore store) {
        PxLogService logService = new PxLogService();
        EmbeddingStoreSupplier supplier = Mockito.mock(EmbeddingStoreSupplier.class);
        Mockito.when(supplier.getStore("cwd")).thenReturn(store);

        new EmbeddingImportService(
                logService,
                new ObjectMapper(),
                supplier,
                new PrjXPJsonStreamProvider(cfg),
                resolver,
                new StoreIdChecker(cfg),
                cfg
        ).execute();
        return logService;
    }

    // ---------- Setup-Helfer ----------

    private PrjXPConfig config() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("a.txt"), "alpha alpha");
        Files.writeString(srcDir.resolve("b.txt"), "bravo bravo");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("cwd");
        pd.setRootDir(srcDir.toString());
        pd.setJsonlFile(tempDir.resolve("px-chunks.jsonl").toString());
        pd.setTibedBatchSize(2);
        pd.setTibedResetStore(false);

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("cwd");
        cfg.getProjects().add(pd);
        return cfg;
    }

    private TransferPasswordResolver resolverWith(String passwordValue) {
        AbstractEnvironment env = new AbstractEnvironment() {
        };
        Map<String, Object> props = new HashMap<>();
        if (passwordValue != null) {
            props.put(PASSWORD_ENV_KEY, passwordValue);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }

    private PrintStream capturingOut() {
        return new PrintStream(new ByteArrayOutputStream(), true);
    }

    private boolean isEncrypted(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII).equals("PRJXPENC");
    }

    private String decrypt(Path file, char[] password) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(bytes), password)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertStoreHasBothChunks(FakeEmbeddingStore store) {
        assertThat(store.allSegments())
                .extracting(TextSegment::text)
                .containsExactlyInAnyOrder("alpha alpha", "bravo bravo");
        assertThat(store.allSegments())
                .extracting(segment -> segment.metadata().toMap().get(PxChunk.PXCHUNK_ID))
                .containsExactlyInAnyOrder("chunk-a.txt", "chunk-b.txt");
        assertThat(store.allEmbeddings())
                .allSatisfy(embedding -> assertThat(embedding.vector()).hasSize(DIMENSION));
    }

    // ---------- Matrixfall 1: Klartext-Ende-zu-Ende ----------

    @Test
    void plaintextEndToEnd_chunkExportImport() throws Exception {
        PrjXPConfig cfg = config();

        runChunkStep(cfg, resolverWith(null), capturingOut());
        Path pxChunks = tempDir.resolve("px-chunks.jsonl");
        assertThat(isEncrypted(pxChunks)).isFalse();

        cfg.getTransfer().setInput(pxChunks.toString());
        cfg.getTransfer().setOutput(tempDir.resolve("embedding.jsonl").toString());
        runExportStep(cfg, resolverWith(null));

        Path embedding = tempDir.resolve("embedding.jsonl");
        assertThat(isEncrypted(embedding)).isFalse();

        cfg.getTransfer().setInput(embedding.toString());
        FakeEmbeddingStore store = new FakeEmbeddingStore();
        runImportStep(cfg, resolverWith(null), store);

        assertStoreHasBothChunks(store);
    }

    // ---------- Matrixfall 2: Verschlüsselt-Ende-zu-Ende mit env-Passwort ----------

    @Test
    void encryptedEndToEnd_withEnvPassword() throws Exception {
        PrjXPConfig cfg = config();

        runChunkStep(cfg, resolverWith("env-pw"), capturingOut());
        Path pxChunks = tempDir.resolve("px-chunks.jsonl");
        assertThat(isEncrypted(pxChunks)).isTrue();

        cfg.getTransfer().setInput(pxChunks.toString());
        cfg.getTransfer().setOutput(tempDir.resolve("embedding.jsonl.enc").toString());
        runExportStep(cfg, resolverWith("env-pw"));

        Path embedding = tempDir.resolve("embedding.jsonl.enc");
        assertThat(isEncrypted(embedding)).isTrue();

        cfg.getTransfer().setInput(embedding.toString());
        FakeEmbeddingStore store = new FakeEmbeddingStore();
        runImportStep(cfg, resolverWith("env-pw"), store);

        assertStoreHasBothChunks(store);
    }

    // ---------- Matrixfall 3: --encrypt ohne env-Passwort (generiert + wiederholt) ----------

    @Test
    void encryptWithoutEnvPassword_generatesAndRepeatsPassword() throws Exception {
        PrjXPConfig cfg = config();
        cfg.getTransfer().setEncrypt(TransferEncryptMode.TRUE);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        runChunkStep(cfg, resolverWith(null), new PrintStream(captured, true));

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("[SECURITY] Generated transfer password:");
        assertThat(output).contains("[SECURITY] REPEAT transfer password:");

        String generated = extractGeneratedPassword(output);
        assertThat(generated).isNotBlank();

        Path pxChunks = tempDir.resolve("px-chunks.jsonl");
        assertThat(isEncrypted(pxChunks)).isTrue();
        assertThat(decrypt(pxChunks, generated.toCharArray())).contains("\"id\":\"chunk-a.txt\"");

        cfg.getTransfer().setInput(pxChunks.toString());
        cfg.getTransfer().setOutput(tempDir.resolve("embedding.jsonl.enc").toString());
        runExportStep(cfg, resolverWith(generated));

        Path embedding = tempDir.resolve("embedding.jsonl.enc");
        assertThat(isEncrypted(embedding)).isTrue();

        cfg.getTransfer().setInput(embedding.toString());
        FakeEmbeddingStore store = new FakeEmbeddingStore();
        runImportStep(cfg, resolverWith(generated), store);

        assertStoreHasBothChunks(store);
    }

    private String extractGeneratedPassword(String output) {
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].contains("[SECURITY] Generated transfer password:")) {
                return lines[i + 1];
            }
        }
        throw new AssertionError("Kein generiertes Passwort in der Ausgabe gefunden:\n" + output);
    }

    // ---------- Matrixfall 4: Falsches Passwort beim Import ----------

    @Test
    void wrongPasswordOnImport_importsNothing() throws Exception {
        PrjXPConfig cfg = config();

        runChunkStep(cfg, resolverWith("correct-pw"), capturingOut());
        Path pxChunks = tempDir.resolve("px-chunks.jsonl");

        cfg.getTransfer().setInput(pxChunks.toString());
        cfg.getTransfer().setOutput(tempDir.resolve("embedding.jsonl.enc").toString());
        runExportStep(cfg, resolverWith("correct-pw"));

        Path embedding = tempDir.resolve("embedding.jsonl.enc");
        cfg.getTransfer().setInput(embedding.toString());

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        PxLogService logService = runImportStep(cfg, resolverWith("wrong-pw"), store);

        assertThat(store.size()).isZero();
        assertThat(logService.getMessagesWithLevelMin(Level.SEVERE)).isNotEmpty();
    }

    // ---------- Matrixfall 5: --no-encrypt trotz vorhandenem env-Passwort ----------

    @Test
    void noEncryptDespiteEnvPassword_writesPlaintext() throws Exception {
        PrjXPConfig cfg = config();
        cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);

        runChunkStep(cfg, resolverWith("env-pw"), capturingOut());
        Path pxChunks = tempDir.resolve("px-chunks.jsonl");
        assertThat(isEncrypted(pxChunks)).isFalse();

        cfg.getTransfer().setInput(pxChunks.toString());
        cfg.getTransfer().setOutput(tempDir.resolve("embedding.jsonl").toString());
        runExportStep(cfg, resolverWith("env-pw"));

        Path embedding = tempDir.resolve("embedding.jsonl");
        assertThat(isEncrypted(embedding)).isFalse();

        cfg.getTransfer().setInput(embedding.toString());
        FakeEmbeddingStore store = new FakeEmbeddingStore();
        runImportStep(cfg, resolverWith("env-pw"), store);

        assertStoreHasBothChunks(store);
    }

    // ---------- Test-Doppel: Chunker (liest Dateiinhalt, erzeugt je ein Chunk) ----------

    private static class FileContentChunker implements PxChunker {
        @Override
        public Stream<PxChunk> chunk(File f) {
            try {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                return Stream.of(PxChunk.create(c -> {
                    c.setId("chunk-" + f.getName());
                    c.setMimeType("text/plain");
                    c.setFile(f.getName());
                    c.setContent(content);
                }));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean matches(File f) {
            return true;
        }
    }

}
