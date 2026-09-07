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
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingImportServiceTest {
    @TempDir
    Path tempDir;

    private static final int DIMENSION = 8;

    /**
     * In-Memory-Store, der das Verhalten des Lucene-Stores nachahmt:
     * Jeder Add erzeugt einen neuen Eintrag (keine automatische Dedup),
     * IDs werden aus dem Segment-Metadaten extrahiert.
     */
    static class FakeStore implements EmbeddingStore<TextSegment> {
        private final Map<String, Embedding> embeddings = new LinkedHashMap<>();
        private final Map<String, TextSegment> segments = new LinkedHashMap<>();
        private int counter = 0;
        final List<Integer> addAllBatchSizes = new ArrayList<>();

        @Override
        public String add(Embedding embedding) {
            return add(embedding, null);
        }

        @Override
        public void add(String id, Embedding embedding) {
            embeddings.put(id, embedding);
        }

        @Override
        public String add(Embedding embedding, TextSegment textSegment) {
            String id = extractId(textSegment) + "#" + (counter++);
            embeddings.put(id, embedding);
            if (textSegment != null) {
                segments.put(id, textSegment);
            }
            return id;
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return embeddings.stream().map(this::add).toList();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
            if (embeddings.size() != textSegments.size()) {
                throw new IllegalArgumentException("size mismatch");
            }
            addAllBatchSizes.add(embeddings.size());
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < embeddings.size(); i++) {
                ids.add(add(embeddings.get(i), textSegments.get(i)));
            }
            return ids;
        }

        private String extractId(TextSegment textSegment) {
            if (textSegment != null) {
                Map<String, Object> meta = textSegment.metadata().toMap();
                if (meta.containsKey("id")) {
                    return meta.get("id").toString();
                }
                if (meta.containsKey(PxChunk.PXCHUNK_ID)) {
                    return meta.get(PxChunk.PXCHUNK_ID).toString();
                }
            }
            return UUID.randomUUID().toString();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (Map.Entry<String, Embedding> entry : embeddings.entrySet()) {
                TextSegment segment = segments.get(entry.getKey());
                if (segment == null) {
                    continue;
                }
                if (request.filter() != null && !matchesFilter(segment, request.filter())) {
                    continue;
                }
                matches.add(new EmbeddingMatch<>(1.0, entry.getKey(), entry.getValue(), segment));
            }
            return new EmbeddingSearchResult<>(matches);
        }

        private boolean matchesFilter(TextSegment segment, Filter filter) {
            Map<String, Object> meta = segment.metadata().toMap();
            if (filter instanceof IsEqualTo eq) {
                return Objects.equals(meta.get(eq.key()), eq.comparisonValue());
            }
            if (filter instanceof IsNotEqualTo ne) {
                return !Objects.equals(meta.get(ne.key()), ne.comparisonValue());
            }
            throw new UnsupportedOperationException("FakeStore supports only equality filters");
        }

        @Override
        public void removeAll(Filter filter) {
            embeddings.keySet().removeIf(id -> {
                TextSegment segment = segments.get(id);
                return segment != null && matchesFilter(segment, filter);
            });
            segments.keySet().removeIf(id -> !embeddings.containsKey(id));
        }

        int size() {
            return embeddings.size();
        }

        List<TextSegment> allSegments() {
            return new ArrayList<>(segments.values());
        }

        List<Embedding> allEmbeddings() {
            return new ArrayList<>(embeddings.values());
        }
    }

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

    private EmbeddingImportService uut(PrjXPConfig cfg, FakeStore store, String passwordValue) {
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

        FakeStore store = new FakeStore();
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

        FakeStore store = new FakeStore();
        uut(cfg, store, null).execute();
        uut(cfg, store, null).execute();

        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void import_withBatchSize2_writesInBatches() throws Exception {
        PrjXPConfig cfg = configWith(tempDir.resolve("embedding.jsonl").toString());
        writeRecords(cfg, "alpha", "bravo", "charlie");

        FakeStore store = new FakeStore();
        uut(cfg, store, null).execute();

        assertThat(store.addAllBatchSizes).containsExactly(2, 1);
    }

    @Test
    void import_withEncryptedInputAndEnvPassword_decryptsBeforeImport() throws Exception {
        String inputPath = tempDir.resolve("embedding.jsonl.enc").toString();
        PrjXPConfig cfg = configWith(inputPath);
        cfg.getTransfer().setPasswordEnv("PRJXP_TEST_PASSWORD_XYZ");
        writeEncryptedRecords(cfg, "env-pw", "alpha alpha");

        FakeStore store = new FakeStore();
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

        FakeStore store = new FakeStore();
        uut(cfg, store, "wrong-pw").execute();

        assertThat(store.size()).isZero();
    }

    @Test
    void import_withResetStore_wipesExistingEntriesFirst() throws Exception {
        String inputPath = tempDir.resolve("embedding.jsonl").toString();
        PrjXPConfig cfg = configWith(inputPath);
        cfg.getActiveProject().orElseThrow().setTibedResetStore(true);
        writeRecords(cfg, "alpha");

        FakeStore store = new FakeStore();
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

        FakeStore store = new FakeStore();
        assertThatThrownBy(() -> uut(cfg, store, null).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("input");

        assertThat(store.size()).isZero();
    }
}
