package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Candidate generation must be a superset of what a line-level match can find: the analyzer splits
 * {@code PreloadEventListener} into one term, so a plain term query for {@code Preload} would miss
 * the class. These tests pin the prefix and the mid-word stage.
 */
class LucenePxChunkDaoCandidatesTest {

    @TempDir
    Path tempDir;

    LuceneEmbeddingStore store;
    LucenePxChunkDao dao;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        PrjXPEmbeddingStoreReference reference = new PrjXPEmbeddingStoreReference();
        reference.setProjectName("test-project");
        dao = new LucenePxChunkDao(store, mock(EmbeddingModel.class), reference);
    }

    private void add(String id, String file, String content) {
        Metadata meta = new Metadata();
        meta.put("pxchunk_id", id);
        meta.put(PxChunk.PXCHUNK_FILE, file);
        meta.put(PxChunk.PXCHUNK_PROJECT, "test-project");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from(content, meta));
    }

    private void addOtherProject(String id, String file, String content) {
        Metadata meta = new Metadata();
        meta.put("pxchunk_id", id);
        meta.put(PxChunk.PXCHUNK_FILE, file);
        meta.put(PxChunk.PXCHUNK_PROJECT, "other-project");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from(content, meta));
    }

    @Test
    void prefixFindsClassNames() {
        add("c1", "a/PreloadEventListener.java", "public class PreloadEventListener {");

        List<ScoredChunk> hits = dao.searchCandidates("Preload", Map.of(), 10);

        assertThat(hits).extracting(sc -> sc.chunk().getFile())
                .containsExactly("a/PreloadEventListener.java");
    }

    @Test
    void candidateIsSupersetOfLineMatches() {
        add("c1", "a/A.java", "run();");
        add("c2", "a/B.java", "runtime exception");

        assertThat(dao.searchCandidates("run", Map.of(), 10))
                .extracting(sc -> sc.chunk().getFile())
                .containsExactlyInAnyOrder("a/A.java", "a/B.java");
    }

    @Test
    void dictionaryStageFindsMidWordMatches() {
        add("c1", "a/PreloadEventListener.java", "class PreloadEventListener implements Listener {");

        List<ScoredChunk> hits = dao.searchCandidates("Listener", Map.of(), 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunk().getId()).isEqualTo("c1");
    }

    @Test
    void candidatesAreProjectScoped() {
        addOtherProject("c1", "a/Preload.java", "class Preload {}");

        assertThat(dao.searchCandidates("Preload", Map.of(), 10)).isEmpty();
    }

    @Test
    void candidatesRespectLimit() {
        for (int i = 0; i < 5; i++) {
            add("c" + i, "a/F" + i + ".java", "needle in file " + i);
        }

        assertThat(dao.searchCandidates("needle", Map.of(), 3)).hasSize(3);
    }

    @Test
    void findFilesContainingIsCaseInsensitive() {
        add("c1", "osb/impl/jar/src/main/java/db/training/hibernate/preload/PreloadEventListener.java", "x");
        add("c2", "osb/impl/jar/src/main/java/db/training/bob/service/ZvfImporter.java", "x");

        assertThat(dao.findFilesContaining("preload", 10))
                .containsExactly("osb/impl/jar/src/main/java/db/training/hibernate/preload/PreloadEventListener.java");
    }

    @Test
    void baseNameMatchesRankBeforePathMatches() {
        add("c1", "osb/preload/PackageInfo.java", "x");
        add("c2", "osb/preload/Preload.java", "x");

        assertThat(dao.findFilesContaining("preload", 10)).containsExactly(
                "osb/preload/Preload.java",
                "osb/preload/PackageInfo.java");
    }

    @Test
    void fileNameGroupsStaySorted() {
        add("c1", "a/PreloadSetting.java", "x");
        add("c2", "a/Preload.java", "x");
        add("c3", "a/PreloadCallback.java", "x");

        assertThat(dao.findFilesContaining("preload", 10)).containsExactly(
                "a/Preload.java",
                "a/PreloadCallback.java",
                "a/PreloadSetting.java");
    }

    @Test
    void unknownTokenYieldsEmptyList() {
        add("c1", "a/Preload.java", "x");

        assertThat(dao.findFilesContaining("zzz", 10)).isEmpty();
        assertThat(dao.findFilesContaining("  ", 10)).isEmpty();
        assertThat(dao.findFilesContaining(null, 10)).isEmpty();
    }

    @Test
    void nameLookupIsProjectScoped() {
        addOtherProject("c1", "a/PreloadOther.java", "x");

        assertThat(dao.findFilesContaining("preload", 10)).isEmpty();
    }

    @Test
    void defaultStoreFallsBackToFullTextAndRefusesNameLookup() {
        PxChunkDao storeWithoutLucene = mock(PxChunkDao.class, CALLS_REAL_METHODS);
        ScoredChunk hit = new ScoredChunk(PxChunk.create(c -> c.setId("c1")), 1.0);
        doReturn(List.of(hit)).when(storeWithoutLucene).searchFullText("q", Map.of(), 5);

        assertThat(storeWithoutLucene.searchCandidates("q", Map.of(), 5)).containsExactly(hit);
        assertThatThrownBy(() -> storeWithoutLucene.findFilesContaining("q", 5))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
