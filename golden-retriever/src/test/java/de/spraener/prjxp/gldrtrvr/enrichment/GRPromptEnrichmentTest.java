package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import de.spraener.prjxp.gldrtrvr.md.MarkdownRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GRPromptEnrichmentTest {

    private final GRPromptEnrichment enrichment = new GRPromptEnrichment(null, new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of()));

    @Test
    void reIterateSnapsOffGridThresholdToNextLowerGridPoint() {
        SearchParams params = new SearchParams(20, 0.93);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.90, offset(1e-9));
        assertThat(params.getEffectiveMinScore()).isCloseTo(0.90, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(1);
    }

    @Test
    void reIterateOnGridThresholdMovesToNextLowerPoint() {
        SearchParams params = new SearchParams(20, 0.85);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.80, offset(1e-9));
    }

    @Test
    void reIterateSequenceFollowsCanonicalGrid() {
        SearchParams params = new SearchParams(20, 0.97);

        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.95, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.90, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.85, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.80, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(4);
    }

    @Test
    void reIterateAbortsBelowHalf() {
        SearchParams params = new SearchParams(20, 0.50);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.45, offset(1e-9));
        assertThat(params.isAbort()).isTrue();
    }

    @Test
    void reIterateStillGrowsMaxResultBelow16() {
        SearchParams params = new SearchParams(8, 0.93);

        enrichment.reIterate(params);

        assertThat(params.getMaxResult()).isEqualTo(10);
        assertThat(params.getMinScore()).isCloseTo(0.93, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(0);
    }

    @Test
    void totalBudgetSkipsRetrieversThatDoNotFit() {
        var withBudget = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(new FakeChunkDao("test"))),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(
                        new FixedRetriever("A".repeat(40_000)),
                        new FixedRetriever("B".repeat(40_000)))));

        String result = withBudget.enrich("test", "query", List.of(),
                c -> "CTX[" + c + "]",
                s -> s.length() > 0);

        assertThat(result).contains("AAAA");
        assertThat(result).doesNotContain("BBBB");
        assertThat(result).contains("Groessenlimit");
    }

    @Test
    void totalBudgetKeepsRetrieversThatFit() {
        var withBudget = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(new FakeChunkDao("test"))),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(
                        new FixedRetriever("A".repeat(10_000)),
                        new FixedRetriever("B".repeat(10_000)))));

        String result = withBudget.enrich("test", "query", List.of(),
                c -> "CTX[" + c + "]",
                s -> s.length() > 0);

        assertThat(result).contains("AAAA");
        assertThat(result).contains("BBBB");
        assertThat(result).doesNotContain("Groessenlimit");
    }

    @Test
    void vectorScoresAreNotRerankedByLeidenWeight() {
        PxChunk central = PxChunk.create(c -> {
            c.setId("central");
            c.setContent("central");
            c.getMetadata().put("leiden_weight", "1.0");
        });
        PxChunk peripheral = PxChunk.create(c -> {
            c.setId("peripheral");
            c.setContent("peripheral");
            c.getMetadata().put("leiden_weight", "0.0");
        });
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(List.of(new ScoredChunk(central, 0.9), new ScoredChunk(peripheral, 0.9)));

        CapturingRetriever retriever = new CapturingRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        withDao.enrich("test", "query", List.of(),
                c -> c,
                s -> s.length() > 0);

        // The retriever path must receive the raw vector scores: the Leiden weight acts
        // additively inside ChunkRankingService (via ChunkNode), never multiplicatively here.
        assertThat(retriever.captured()).extracting(ScoredChunk::score)
                .containsExactly(0.9, 0.9);
    }

    // ---- Phase 02: test-class filter ----

    private static PxChunk chunk(String id, String file, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFile(file);
            c.setContent(content);
        });
    }

    private static List<ScoredChunk> mixedHits() {
        return List.of(
                new ScoredChunk(chunk("main-a", "src/main/java/A.java", "MAIN-A-CONTENT"), 0.9),
                new ScoredChunk(chunk("main-b", "src/main/java/B.java", "MAIN-B-CONTENT"), 0.9),
                new ScoredChunk(chunk("test-a", "src/test/java/ATest.java", "TEST-A-CONTENT"), 0.9),
                new ScoredChunk(chunk("test-b", "src/test/java/BTest.java", "TEST-B-CONTENT"), 0.9));
    }

    @Test
    void includeTestsFalseFiltersTestChunksFromPrompt() {
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        String result = withDao.enrich("test", "query", List.of(),
                new SearchParams(20, 0.85),
                sp -> sp,
                c -> c,
                false,
                s -> s.length() > 0);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getFile())
                .containsExactly("src/main/java/A.java", "src/main/java/B.java");
        assertThat(result)
                .contains("MAIN-A-CONTENT").contains("MAIN-B-CONTENT")
                .doesNotContain("TEST-A-CONTENT").doesNotContain("TEST-B-CONTENT");
    }

    @Test
    void includeTestsTrueKeepsAllChunks() {
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        String result = withDao.enrich("test", "query", List.of(),
                new SearchParams(20, 0.85),
                sp -> sp,
                c -> c,
                true,
                s -> s.length() > 0);

        assertThat(retriever.captured()).hasSize(4);
        assertThat(result)
                .contains("MAIN-A-CONTENT").contains("MAIN-B-CONTENT")
                .contains("TEST-A-CONTENT").contains("TEST-B-CONTENT");
    }

    @Test
    void legacyOverloadsDefaultToIncludeTestsTrue() {
        // Status quo for docpipe/ChatService: the old overloads keep test chunks
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        withDao.enrich("test", "query", 0.85, 20, false);

        assertThat(retriever.captured()).hasSize(4);
    }

    @Test
    void threeArgOverloadFiltersTestChunks() {
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        String result = withDao.enrich("test", "query", false);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getFile())
                .containsExactly("src/main/java/A.java", "src/main/java/B.java");
        assertThat(result).contains("Relevante Information aus dem Projekt 'test'")
                .doesNotContain("TEST-A-CONTENT");
    }

    @Test
    void twoArgOverloadKeepsTestChunks() {
        // Status quo: the plain overload keeps test chunks (includeTests=true)
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        String result = withDao.enrich("test", "query");

        assertThat(retriever.captured()).hasSize(4);
        assertThat(result).contains("TEST-A-CONTENT");
    }

    @Test
    void sixArgMainOverloadDelegatesWithIncludeTestsTrue() {
        // The old main overload (docpipe/ChatService) keeps test chunks and maps prefetched chunks
        FakeChunkDao dao = new FakeChunkDao("test");
        dao.setVectorResults(mixedHits());

        ContentRetriever retriever = new ContentRetriever();
        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));

        PxChunk prefetched = chunk("pref", "src/main/java/Pref.java", "PREF-CONTENT");

        String result = withDao.enrich("test", "query", List.of(prefetched),
                new SearchParams(20, 0.85),
                sp -> sp,
                c -> c,
                s -> s.length() > 0);

        assertThat(retriever.captured()).hasSize(5);
        assertThat(result).contains("PREF-CONTENT").contains("TEST-A-CONTENT");
    }

    // ---- Phase 03b: size budget via doc-chunk windowing (vectorSearch path) ----

    @Test
    void largeMarkdownChunkKeepsEnrichedPromptWithinBudget() {
        String content = "lorem ipsum dolor\n".repeat(1200);   // ~22 KB
        PxChunk md = PxChunk.create(c -> {
            c.setId("big.md");
            c.setMimeType("text/markdown");
            c.setFile("docs/big.md");
            c.setContent(content);
            c.setPart(0);
            c.setTotal(1);
            c.getMetadata().put(PxChunk.PXCHUNK_TYPE, "PARAGRAPH");
        });

        PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        ref.setProjectName("test");
        ref.setDefault(true);
        PxChunkDao dao = new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return ref;
            }

            @Override
            public List<PxChunk> findById(String id) {
                // Return a mutable copy: PxChunk.combine sorts the list in place (like the Lucene DAO).
                return "big.md".equals(id) ? new java.util.ArrayList<>(List.of(md)) : new java.util.ArrayList<>();
            }

            @Override
            public List<PxChunk> findByMetaData(Map<String, String> metaData) {
                return List.of();
            }

            @Override
            public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
                return List.of();
            }

            @Override
            public Stream<PxChunk> findAll() {
                return Stream.empty();
            }

            @Override
            public List<ScoredChunk> searchVector(String query, Map<String, String> filters, int limit) {
                return List.of(new ScoredChunk(md, 0.9));
            }
        };

        MarkdownRetriever mdRetriever = new MarkdownRetriever(
                new PxChunkDaoProvider(List.of(dao)),
                new ChunkRankingService(List.of(new ChunkRankingStrategy() {
                    @Override
                    public boolean supports(PxChunk chunk) {
                        return true;
                    }

                    @Override
                    public double rank(PxChunk chunk) {
                        return 1.0;
                    }
                }), new GraphRerankConfig()));

        var withDao = new GRPromptEnrichment(
                new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(mdRetriever)));

        String result = withDao.enrich("test", "query", List.of(),
                c -> c,
                s -> s.length() > 0);

        assertThat(result).contains("readFile(\"docs/big.md\")");
        assertThat(result.length())
                .as("the 22 KB chunk must be windowed down to ~5 KB, not dumped in full")
                .isLessThanOrEqualTo(5000);
    }

    private static class FakeChunkDao implements PxChunkDao {
        private final PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        private List<ScoredChunk> vectorResults;

        FakeChunkDao(String projectName) {
            ref.setProjectName(projectName);
        }

        void setVectorResults(List<ScoredChunk> results) {
            this.vectorResults = results;
        }

        @Override
        public PrjXPEmbeddingStoreReference getStoreReference() {
            return ref;
        }

        @Override
        public List<PxChunk> findById(String id) {
            return List.of();
        }

        @Override
        public List<PxChunk> findByMetaData(Map<String, String> metaData) {
            return List.of();
        }

        @Override
        public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
            return List.of();
        }

        @Override
        public Stream<PxChunk> findAll() {
            return Stream.empty();
        }

        @Override
        public List<ScoredChunk> searchVector(String query, Map<String, String> filters, int limit) {
            if (vectorResults == null) {
                throw new UnsupportedOperationException("Vector search is not supported by this store");
            }
            return vectorResults;
        }
    }

    private static class CapturingRetriever implements GoldenRetriever {
        private List<ScoredChunk> captured = List.of();

        List<ScoredChunk> captured() {
            return captured;
        }

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            this.captured = chunks;
            return new StringBuilder("captured");
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }

    private static class ContentRetriever implements GoldenRetriever {
        private List<ScoredChunk> captured = List.of();

        List<ScoredChunk> captured() {
            return captured;
        }

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            this.captured = chunks;
            StringBuilder sb = new StringBuilder();
            for (ScoredChunk sc : chunks) {
                if (sc.chunk().getContent() != null) {
                    sb.append(sc.chunk().getContent());
                }
            }
            return sb;
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }

    private static class FixedRetriever implements GoldenRetriever {
        private final String content;

        FixedRetriever(String content) {
            this.content = content;
        }

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            return new StringBuilder(content);
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }
}
