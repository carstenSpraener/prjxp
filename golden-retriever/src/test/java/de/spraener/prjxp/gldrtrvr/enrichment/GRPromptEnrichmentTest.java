package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GRPromptEnrichmentTest {

    private final GRPromptEnrichment enrichment = new GRPromptEnrichment(null, List.of());

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
                List.of(
                        new FixedRetriever("A".repeat(40_000)),
                        new FixedRetriever("B".repeat(40_000))));

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
                List.of(
                        new FixedRetriever("A".repeat(10_000)),
                        new FixedRetriever("B".repeat(10_000))));

        String result = withBudget.enrich("test", "query", List.of(),
                c -> "CTX[" + c + "]",
                s -> s.length() > 0);

        assertThat(result).contains("AAAA");
        assertThat(result).contains("BBBB");
        assertThat(result).doesNotContain("Groessenlimit");
    }

    private static class FakeChunkDao implements PxChunkDao {
        private final PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();

        FakeChunkDao(String projectName) {
            ref.setProjectName(projectName);
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
