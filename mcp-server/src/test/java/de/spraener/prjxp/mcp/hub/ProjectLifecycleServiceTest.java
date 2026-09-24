package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests full project deletion against a REAL registry + real Lucene index (8-dim):
 * scoped index wipe, recursive directory removal and unregistration.
 */
class ProjectLifecycleServiceTest {

    @TempDir
    Path tempRoot;

    private HubProperties hub;
    private PrjXPConfig cfg;
    private PxChunkDaoProvider provider;
    private LuceneEmbeddingStore luceneStore;
    private EmbeddingModel embeddingModel;   // mocked — never invoked here
    private ProjectConfigFileParser parser;
    private HubProjectRegistry registry;
    private ProjectLifecycleService lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        Path projectsRoot = Files.createDirectories(tempRoot.resolve("projects"));
        Path indexDir = Files.createDirectories(tempRoot.resolve("index"));

        hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        cfg = new PrjXPConfig();
        provider = new PxChunkDaoProvider(List.of());
        luceneStore = new LuceneEmbeddingStore(indexDir, 8);
        embeddingModel = mock(EmbeddingModel.class);
        parser = new ProjectConfigFileParser();
        registry = new HubProjectRegistry(cfg, hub, provider, luceneStore, embeddingModel, parser);
        lifecycle = new ProjectLifecycleService(registry, luceneStore, hub);
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private TextSegment segmentFor(String project) {
        return TextSegment.from("content of " + project,
                Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, project)));
    }

    @Test
    void deleteWipesScopedIndexChunksDirectoryAndEntry() throws Exception {
        Path dir = Files.createDirectories(tempRoot.resolve("projects").resolve("alpha"));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src").resolve("Foo.java"), "class Foo {}");

        registry.registerProject("alpha", dir);
        luceneStore.addAll(
                List.of(Embedding.from(new float[8]), Embedding.from(new float[8])),
                List.of(segmentFor("alpha"), segmentFor("beta")));

        lifecycle.delete("alpha");

        // scoped wipe: 'alpha' is gone from the shared index, other projects untouched
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "alpha"))).isFalse();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "beta"))).isTrue();
        assertThat(dir).doesNotExist();
        assertThat(registry.entry("alpha")).isEmpty();
    }

    @Test
    void deleteOfUnknownProjectThrows() {
        assertThatThrownBy(() -> lifecycle.delete("ghost"))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void deleteToleratesAlreadyMissingDirectory() throws Exception {
        Path dir = Files.createDirectories(tempRoot.resolve("projects").resolve("gone"));
        registry.registerProject("gone", dir);

        // simulate external removal of the project directory (bottom-up)
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // already gone
                        }
                    });
        }

        lifecycle.delete("gone");   // must not throw on the missing directory

        assertThat(registry.entry("gone")).isEmpty();
    }
}
