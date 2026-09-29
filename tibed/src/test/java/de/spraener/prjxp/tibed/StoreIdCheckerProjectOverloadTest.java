package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 01 (DockerHub): the project-scoped overloads of {@link StoreIdChecker}
 * must scope chunk lookups to the explicitly given project, while the 2-arg
 * overloads keep using the configured active project.
 */
class StoreIdCheckerProjectOverloadTest {

    private static final int DIM = 8;

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), DIM);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void addChunk(String id, String project) {
        Metadata metadata = Metadata.from(Map.of(
                de.spraener.prjxp.common.model.PxChunk.PXCHUNK_ID, id,
                de.spraener.prjxp.common.model.PxChunk.PXCHUNK_PROJECT, project));
        store.add(Embedding.from(new float[DIM]), TextSegment.from("content of " + id, metadata));
    }

    private StoreIdChecker checkerWithActiveProject(String activeProject) {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        if (activeProject != null) {
            ProjectDefinition pd = new ProjectDefinition();
            pd.setName(activeProject);
            when(cfg.getActiveProject()).thenReturn(Optional.of(pd));
        } else {
            when(cfg.getActiveProject()).thenReturn(Optional.empty());
        }
        return new StoreIdChecker(cfg);
    }

    @Test
    void threeArgContainsChunkIsScopedToGivenProject() {
        addChunk("chunk-A", "A");

        StoreIdChecker checker = checkerWithActiveProject(null);

        assertThat(checker.containsChunk(store, "chunk-A", "A")).isTrue();
        assertThat(checker.containsChunk(store, "chunk-A", "B")).isFalse();
    }

    @Test
    void nullProjectMatchesOnChunkIdOnly() {
        addChunk("chunk-A", "A");

        StoreIdChecker checker = checkerWithActiveProject(null);

        assertThat(checker.containsChunk(store, "chunk-A", null)).isTrue();
    }

    @Test
    void twoArgOverloadUsesCfgActiveProject() {
        addChunk("chunk-A", "A");

        assertThat(checkerWithActiveProject("A").containsChunk(store, "chunk-A")).isTrue();
        assertThat(checkerWithActiveProject("B").containsChunk(store, "chunk-A")).isFalse();
    }

    @Test
    void needsImportOverloadsMirrorContainsChunk() {
        addChunk("chunk-A", "A");

        StoreIdChecker checker = checkerWithActiveProject("B");

        assertThat(checker.needsImport(store, "chunk-A", "A")).isFalse();
        assertThat(checker.needsImport(store, "chunk-A", "B")).isTrue();
        // 2-arg overload resolves against the cfg active project ("B") -> import needed
        assertThat(checker.needsImport(store, "chunk-A")).isTrue();
    }

    @Test
    void blankChunkIdIsNeverContained() {
        addChunk("chunk-A", "A");

        StoreIdChecker checker = checkerWithActiveProject(null);

        assertThat(checker.containsChunk(store, "  ", "A")).isFalse();
        assertThat(checker.needsImport(store, "", "A")).isTrue();
    }
}
