package de.spraener.prjxp.pluginfix.good;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Test fixture: a well-behaved external pack with both engine sides —
 * a chunker that produces real PxChunks and a retriever.
 * Compiled at test time against the host classpath and packaged into a JAR.
 */
public class GoodPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new GoodPack();
    }

    public static class GoodPack implements LanguagePack {
        @Override
        public String language() {
            return "goodlang";
        }

        @Override
        public String mimeType() {
            return "text/x-goodlang-code";
        }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.of(new GoodChunker());
        }

        @Override
        public Optional<GoldenRetriever> retriever() {
            return Optional.of(new GoodRetriever());
        }
    }

    public static class GoodRetriever implements GoldenRetriever {
        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            return new StringBuilder("good-retriever");
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }

    public static class GoodChunker implements PxChunker {
        @Override
        public Stream<PxChunk> chunk(File f) {
            return Stream.of(PxChunk.create(c -> {
                c.setId("good-1");
                c.setMimeType("text/x-goodlang-code");
                c.setFile(f.getAbsolutePath());
                c.setPart(0);
                c.setTotal(1);
                c.setContent("hello from the plugin");
            }));
        }

        @Override
        public boolean matches(File f) {
            return f.getName().endsWith(".good");
        }
    }
}
