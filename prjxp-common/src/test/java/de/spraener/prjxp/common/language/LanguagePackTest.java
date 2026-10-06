package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguagePackTest {

    @Test
    void defaultFindPxChunkersReturnsEmptyStream() {
        LanguagePack pack = new MinimalLanguagePack();
        assertThat(pack.findPxChunkers(new File("/tmp/test.java"))).isEmpty();
    }

    @Test
    void defaultListPostWalkChunkersReturnsEmptyStream() {
        LanguagePack pack = new MinimalLanguagePack();
        assertThat(pack.listPostWalkChunkers()).isEmpty();
    }

    @Test
    void defaultRetrieverReturnsEmptyOptional() {
        LanguagePack pack = new MinimalLanguagePack();
        assertThat(pack.retriever()).isEmpty();
    }

    @Test
    void fullImplementationReturnsExpectedValues() {
        FullLanguagePack pack = new FullLanguagePack();

        assertThat(pack.language()).isEqualTo("fulllang");
        assertThat(pack.mimeType()).isEqualTo("text/x-fulllang");

        Stream<PxChunker> chunkers = pack.findPxChunkers(new File("/tmp/test.full"));
        assertThat(chunkers).hasSize(1);

        Stream<PxChunker> postWalk = pack.listPostWalkChunkers();
        assertThat(postWalk).hasSize(1);

        Optional<GoldenRetriever> retriever = pack.retriever();
        assertThat(retriever).isPresent();
    }

    /* --- Test helpers --- */

    private static class MinimalLanguagePack implements LanguagePack {
        @Override public String language() { return "minimal"; }
        @Override public String mimeType() { return "text/x-minimal"; }
    }

    private static class FullLanguagePack implements LanguagePack {
        @Override public String language() { return "fulllang"; }
        @Override public String mimeType() { return "text/x-fulllang"; }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.of(new TestChunker());
        }

        @Override
        public Stream<PxChunker> listPostWalkChunkers() {
            return Stream.of(new TestChunker());
        }

        @Override
        public Optional<GoldenRetriever> retriever() {
            return Optional.of(new TestRetriever());
        }

        private static class TestChunker implements PxChunker {
            @Override public java.util.stream.Stream<de.spraener.prjxp.common.model.PxChunk> chunk(File f) { return java.util.stream.Stream.empty(); }
            @Override public boolean matches(File f) { return true; }
        }

        private static class TestRetriever implements GoldenRetriever {
            @Override
            public java.lang.StringBuilder buildPromptForFindings(String projectName, java.util.List<de.spraener.prjxp.common.model.ScoredChunk> chunks, de.spraener.prjxp.common.retrieval.SearchParams params, java.util.function.Function<String, Boolean>... contextValidators) {
                return new StringBuilder();
            }

            @Override
            public java.util.List<de.spraener.prjxp.common.model.SearchHit> retrieveSearchHits(String projectName, java.util.List<de.spraener.prjxp.common.model.ScoredChunk> chunks, java.util.function.Function<String, Boolean>... contextValidators) {
                return java.util.List.of();
            }
        }
    }
}
