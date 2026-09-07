package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingExportServiceTest {
    @TempDir
    Path tempDir;

    private static final int DIMENSION = 8;

    private static class FakeEmbeddingModel implements EmbeddingModel {
        final int dimension;
        final List<List<TextSegment>> calls = new ArrayList<>();

        FakeEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls.add(segments);
            List<Embedding> embeddings = segments.stream()
                    .map(s -> {
                        float[] vector = new float[dimension];
                        int value = s.text().length();
                        for (int i = 0; i < dimension; i++) {
                            vector[i] = value / 10f + i * 0.1f;
                        }
                        return Embedding.from(vector);
                    })
                    .toList();
            return new Response<>(embeddings);
        }
    }

    private PrjXPConfig configWith(String input, String output) throws Exception {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("cwd");
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile(input);
        pd.setTibedBatchSize(2);

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("cwd");
        cfg.getProjects().add(pd);
        cfg.getTransfer().setInput(input);
        cfg.getTransfer().setOutput(output);
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

    private EmbeddingExportService uut(PrjXPConfig cfg, FakeEmbeddingModel model) {
        return new EmbeddingExportService(
                org.mockito.Mockito.mock(PxLogService.class),
                new ObjectMapper(),
                model,
                new PrjXPJsonStreamProvider(cfg),
                resolverWith("PRJXP_TEST_PASSWORD_XYZ", null),
                cfg
        );
    }

    private void writeInput(PrjXPConfig cfg, String... contents) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.length; i++) {
            final int index = i;
            final String content = contents[i];
            PxChunk chunk = PxChunk.create(c -> {
                c.setId("chunk-" + index);
                c.setMimeType("text/plain");
                c.setFile("f" + index + ".txt");
                c.setContent(content);
            });
            sb.append(mapper.writeValueAsString(chunk)).append('\n');
        }
        Files.writeString(Path.of(cfg.getActiveProject().orElseThrow().getJsonlFile()), sb.toString());
    }

    private List<EmbeddedChunkRecord> readRecords(String output) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return Files.readAllLines(Path.of(output), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return mapper.readValue(line, EmbeddedChunkRecord.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }

    @Test
    void export_withThreeChunks_writesOneRecordPerChunk() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("in.jsonl").toString(), tempDir.resolve("embedding.jsonl").toString());
        writeInput(cfg, "alpha", "bravo bravo", "charlie charlie charlie");

        uut(cfg, new FakeEmbeddingModel(DIMENSION)).execute();

        List<EmbeddedChunkRecord> records = readRecords(tempDir.resolve("embedding.jsonl").toString());
        assertThat(records).hasSize(3);
        assertThat(records.get(0).id()).isEqualTo("chunk-0");
        assertThat(records.get(1).content()).isEqualTo("bravo bravo");
        assertThat(records.get(2).vector()).hasSize(DIMENSION);
    }

    @Test
    void export_withBatchSize2_embedsInBatches() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("in.jsonl").toString(), tempDir.resolve("embedding.jsonl").toString());
        writeInput(cfg, "a", "b", "c");

        FakeEmbeddingModel model = new FakeEmbeddingModel(DIMENSION);
        uut(cfg, model).execute();

        assertThat(model.calls).hasSize(2);
        assertThat(model.calls.get(0)).hasSize(2);
        assertThat(model.calls.get(1)).hasSize(1);
    }

    @Test
    void export_withEmptyContent_skipsChunk() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("in.jsonl").toString(), tempDir.resolve("embedding.jsonl").toString());
        writeInput(cfg, "alpha", "");

        FakeEmbeddingModel model = new FakeEmbeddingModel(DIMENSION);
        uut(cfg, model).execute();

        List<EmbeddedChunkRecord> records = readRecords(tempDir.resolve("embedding.jsonl").toString());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).id()).isEqualTo("chunk-0");
    }

    @Test
    void export_withEnvPassword_writesEncryptedFile() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("in.jsonl").toString(), tempDir.resolve("embedding.jsonl.enc").toString());
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_PASSWORD_XYZ");
        writeInput(cfg, "alpha");

        EmbeddingExportService uut = new EmbeddingExportService(
                org.mockito.Mockito.mock(PxLogService.class),
                new ObjectMapper(),
                new FakeEmbeddingModel(DIMENSION),
                new PrjXPJsonStreamProvider(cfg),
                resolverWith("PRJXP_TEST_PASSWORD_XYZ", "env-pw"),
                cfg
        );
        uut.execute();

        byte[] bytes = Files.readAllBytes(tempDir.resolve("embedding.jsonl.enc"));
        assertThat(new String(bytes, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("PRJXPENC");

        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(bytes), "env-pw".toCharArray())) {
            String decrypted = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(decrypted).contains("\"id\":\"chunk-0\"");
        }
    }

    @Test
    void export_withoutOutput_failsClearly() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("in.jsonl").toString(), null);
        writeInput(cfg, "alpha");

        assertThatThrownBy(() -> uut(cfg, new FakeEmbeddingModel(DIMENSION)).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("output");
    }

    @Test
    void export_withEncryptedInputAndEnvPassword_decryptsBeforeEmbedding() throws Exception {
        String inputPath = tempDir.resolve("in.jsonl.enc").toString();
        PrjXPConfig cfg = configWith(inputPath, tempDir.resolve("embedding.jsonl").toString());
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_PASSWORD_XYZ");
        cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);

        ObjectMapper mapper = new ObjectMapper();
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-0");
            c.setMimeType("text/plain");
            c.setFile("f.txt");
            c.setContent("alpha alpha");
        });
        String plaintext = mapper.writeValueAsString(chunk) + "\n";
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, "env-pw".toCharArray())) {
            out.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(tempDir.resolve("in.jsonl.enc"), raw.toByteArray());

        EmbeddingExportService uut = new EmbeddingExportService(
                org.mockito.Mockito.mock(PxLogService.class),
                mapper,
                new FakeEmbeddingModel(DIMENSION),
                new PrjXPJsonStreamProvider(cfg),
                resolverWith("PRJXP_TEST_PASSWORD_XYZ", "env-pw"),
                cfg
        );
        uut.execute();

        List<EmbeddedChunkRecord> records = readRecords(tempDir.resolve("embedding.jsonl").toString());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).id()).isEqualTo("chunk-0");
    }
}
