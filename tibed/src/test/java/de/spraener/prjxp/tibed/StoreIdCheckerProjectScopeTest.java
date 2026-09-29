package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StoreIdCheckerProjectScopeTest {

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private PrjXPConfig configWithActiveProject(String name) {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName(name);

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject(name);
        cfg.getProjects().add(pd);
        return cfg;
    }

    private void storeWithChunk(String project) {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        Metadata meta = new Metadata();
        meta.put(PxChunk.PXCHUNK_ID, "c1");
        meta.put(PxChunk.PXCHUNK_PROJECT, project);
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("content", meta));
    }

    @Test
    void containsChunkTrueForOwnProject() {
        storeWithChunk("projA");

        StoreIdChecker checker = new StoreIdChecker(configWithActiveProject("projA"));

        assertThat(checker.containsChunk(store, "c1")).isTrue();
    }

    @Test
    void containsChunkFalseForOtherProject() {
        storeWithChunk("projA");

        StoreIdChecker checker = new StoreIdChecker(configWithActiveProject("projB"));

        assertThat(checker.containsChunk(store, "c1")).isFalse();
    }
}
