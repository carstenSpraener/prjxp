package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SetupIndexGuardTest {
    @TempDir Path directory;

    ConfigurationStore store() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), """
                prjxp:
                  embedding-store-lucene:
                    index-path: '%s'
                    vector-dimension: 2
                """.formatted(directory.resolve("index")));
        return new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
    }

    EmbeddingModel model(float... vector) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(TextSegment.class))).thenReturn(Response.from(Embedding.from(vector)));
        return model;
    }

    @Test
    void emptyIndexNeedsNoNetworkAndIsNotCreatedByRead() throws Exception {
        ConfigurationStore store = store();
        SetupIndexGuard guard = new SetupIndexGuard(store);
        EmbeddingModel model = model(1, 0);
        var validation = guard.verify(store.readSnapshot().root(), Map.of("model", "candidate"), model);
        assertThat(validation.samples()).isZero();
        assertThat(directory.resolve("index")).doesNotExist();
        verifyNoInteractions(model);
    }

    @Test
    void unknownLegacyIndexRequiresExplicitSampleTestAndAcceptsVectorCompatibility() throws Exception {
        ConfigurationStore store = store();
        try (var index = new IndexFixture(directory.resolve("index"))) {
            index.store.add(Embedding.from(new float[]{1, 0}), TextSegment.from("sample"));
            SetupIndexGuard guard = new SetupIndexGuard(store);
            Map<String, Object> root = store.readSnapshot().root(), candidate = Map.of("model", "different-name");
            assertThatThrownBy(() -> guard.require(root, candidate, Map.of("dimensions", 2)))
                    .isInstanceOf(SetupException.class);
            var validation = guard.verify(root, candidate, model(1, 0));
            assertThat(validation.samples()).isEqualTo(1);
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("dimensions", 2);
            proof.put("index-fingerprint", validation.fingerprint());
            assertThatCode(() -> guard.require(root, candidate, proof)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.verify(root, candidate, model(0, 1))).isInstanceOf(SetupException.class);
            assertThatThrownBy(() -> guard.verify(root, candidate, model(1, 0, 0))).isInstanceOf(SetupException.class);
            assertThatThrownBy(() -> guard.verify(root, candidate, model(Float.NaN, 0))).isInstanceOf(SetupException.class);
            assertThat(index.store.count()).isEqualTo(1);
        }
    }

    @Test
    void changedSampleOrDimensionInvalidatesCompatibilityEvidenceWithoutMutatingIndex() throws Exception {
        ConfigurationStore store = store();
        try (var index = new IndexFixture(directory.resolve("index"))) {
            index.store.add(Embedding.from(new float[]{1, 0}), TextSegment.from("sample"));
            SetupIndexGuard guard = new SetupIndexGuard(store);
            Map<String, Object> root = store.readSnapshot().root(), candidate = Map.of("model", "candidate");
            var validation = guard.verify(root, candidate, model(1, 0));
            assertThatThrownBy(() -> guard.require(root, candidate,
                    Map.of("dimensions", 3, "index-fingerprint", validation.fingerprint()))).isInstanceOf(SetupException.class);
            index.store.removeAll(new dev.langchain4j.store.embedding.filter.comparison.IsEqualTo("content", "sample"));
            index.store.add(Embedding.from(new float[]{0, 1}), TextSegment.from("different"));
            assertThatThrownBy(() -> guard.require(root, candidate,
                    Map.of("dimensions", 2, "index-fingerprint", validation.fingerprint()))).isInstanceOf(SetupException.class);
        }
    }

    private static class IndexFixture implements AutoCloseable {
        final LuceneEmbeddingStore store;
        IndexFixture(Path path) { store = new LuceneEmbeddingStore(path, 2); }
        public void close() { store.close(); }
    }
}
