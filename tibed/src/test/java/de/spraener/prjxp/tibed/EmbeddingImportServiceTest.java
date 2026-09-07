package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingImportServiceTest {
    @TempDir
    Path tempDir;

    private static final int DIMENSION = 8;

    private final PxLogService logService = Mockito.mock(PxLogService.class);

    private PrjXPConfig configWith(String input) {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("cwd");
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile(input);
        pd.setTibedBatchSize(2);

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("cwd");
        cfg.getProjects().add(pd);
        cfg.getTransfer().setInput(input);
        return cfg;
    }

    private TransferPasswordResolver resolverWith(String key, String value) {
        AbstractEnvironment env = new AbstractEnvironment() {
        };
        Map<String, Object> props = new HashMap<>();
        if (value != null) {
            props.put(key, value);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }

    private EmbeddingImportService uut(PrjXPConfig cfg, FakeEmbeddingStore store, String passwordValue) {
        EmbeddingStoreSupplier supplier = Mockito.mock(EmbeddingStoreSupplier.class);
        Mockito.when(supplier.getStore("cwd")).thenReturn(store);
        return new EmbeddingImportService(
                logService,
                new ObjectMapper(),
                supplier,
                new PrjXPJsonStreamProvider(cfg),
                resolverWith("PRJXP_TEST_PASSWORD_XYZ", passwordValue),
                new StoreIdChecker(cfg),
                cfg
        );
    }

    private void writeRecords(PrjXPConfig cfg, String... contents) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.length; i++) {
            final int index = i;
            PxChunk chunk = PxChunk.create(c -> {
                c.setId("chunk-" + index);
                c.setMimeType("text/plain");
                c.setFile("f" + index + ".txt");
                c.setContent(contents[index]);
            });
            float[] vector = new float[DIMENSION];
            Arrays.fill(vector, index + 1f);
            EmbeddedChunkRecord record = EmbeddedChunkRecord.from(chunk, vector);
            sb.append(mapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(Path.of(cfg.getActiveProject().orElseThrow().getJsonlFile()), sb.toString());
    }

    private void writeEncryptedRecords(PrjXPConfig cfg, String password, String... contents) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.length; i++) {
            final int index = i;
            PxChunk chunk = PxChunk.create(c -> {
                c.setId("chunk-" + index);
                c.setMimeType("text/plain");
                c.setFile("f" + index + ".txt");
                c.setContent(contents[index]);
            });
            float[] vector = new float[DIMENSION];
            Arrays.fill(vector, index + 1f);
            EmbeddedChunkRecord record = EmbeddedChunkRecord.from(chunk, vector);
            sb.append(mapper.writeValueAsString(record)).append('\n');
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, password.toCharArray())) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.write(Path.of(cfg.getActiveProject().orElseThrow().getJsonlFile()), raw.toByteArray());
    }

    @Test
    void import_withThreeRecords_writesAllToStore() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("embedding.jsonl").toString());
        writeRecords(cfg, "alpha", "bravo bravo", "charlie charlie");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        uut(cfg, store, null).execute();

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.allSegments())
                .extracting(TextSegment::text)
                .containsExactly("alpha", "bravo bravo", "charlie charlie");
        assertThat(store.allSegments().get(0).metadata().toMap().get(PxChunk.PXCHUNK_ID)).isEqualTo("chunk-0");
        assertThat(store.allEmbeddings().get(0).vector()).hasSize(DIMENSION);
    }

    @Test
    void import_twice_noDuplicates() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("embedding.jsonl").toString());
        writeRecords(cfg, "alpha", "bravo", "charlie");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        uut(cfg, store, null).execute();
        uut(cfg, store, null).execute();

        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void import_withBatchSize2_writesInBatches() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("embedding.jsonl").toString());
        writeRecords(cfg, "alpha", "bravo", "charlie");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        uut(cfg, store, null).execute();

        assertThat(store.addAllBatchSizes).containsExactly(2, 1);
    }

    @Test
    void import_withEncryptedInputAndEnvPassword_decryptsBeforeImport() throws Exception {
        String inputPath = tempDir.resolve("embedding.jsonl.enc").toString();
        PrjXPConfig cfg = configWith(inputPath);
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_PASSWORD_XYZ");
        writeEncryptedRecords(cfg, "env-pw", "alpha alpha");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        uut(cfg, store, "env-pw").execute();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.allSegments().get(0).text()).isEqualTo("alpha alpha");
    }

    @Test
    void import_withEncryptedInputAndWrongPassword_importsNothing() throws Exception {
        String inputPath = tempDir.resolve("embedding.jsonl.enc").toString();
        PrjXPConfig cfg = configWith(inputPath);
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_PASSWORD_XYZ");
        writeEncryptedRecords(cfg, "env-pw", "alpha alpha");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        uut(cfg, store, "wrong-pw").execute();

        assertThat(store.size()).isZero();
    }

    @Test
    void import_withResetStore_wipesExistingEntriesFirst() throws Exception {
        String inputPath = tempDir.resolve("embedding.jsonl").toString();
        PrjXPConfig cfg = configWith(inputPath);
        cfg.getActiveProject().orElseThrow().setTibedResetStore(true);
        writeRecords(cfg, "alpha");

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        Metadata oldMeta = new Metadata();
        oldMeta.put(PxChunk.PXCHUNK_ID, "old-1");
        store.add(Embedding.from(new float[DIMENSION]), TextSegment.from("old content", oldMeta));

        uut(cfg, store, null).execute();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.allSegments().get(0).text()).isEqualTo("alpha");
    }

    @Test
    void import_withoutInput_failsClearly() throws Exception {
        PrjXPConfig cfg = configWith(null);

        FakeEmbeddingStore store = new FakeEmbeddingStore();
        assertThatThrownBy(() -> uut(cfg, store, null).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("input");

        assertThat(store.size()).isZero();
    }
}
