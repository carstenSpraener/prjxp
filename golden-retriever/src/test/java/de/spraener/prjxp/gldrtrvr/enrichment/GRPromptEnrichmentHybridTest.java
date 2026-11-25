package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic channel alone breaks down on single-token queries: a cosine score for "Preload"
 * has no context to calibrate against, so eight fallback rounds were not enough and vectorSearch
 * aborted. The lexical channel answers the exact token instead of the meaning, and the fusion
 * keeps the semantic top where both agree.
 */
class GRPromptEnrichmentHybridTest {

    private FakeDao dao;
    private CapturingRetriever retriever;
    private GRPromptEnrichment enrichment;

    @BeforeEach
    void setUp() {
        dao = new FakeDao();
        retriever = new CapturingRetriever();
        enrichment = new GRPromptEnrichment(new PxChunkDaoProvider(List.of(dao)),
                new RetrieverRegistry(new LanguagePackRegistry(List.of(), List.of()), List.of(retriever)));
    }

    private static PxChunk chunk(String id, String file, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFile(file);
            c.setContent(content);
        });
    }

    @Test
    void singleTokenQueryWithoutVectorHitIsRescued() {
        dao.vectorResults = List.of();   // everything below the threshold
        dao.lexicalResults = List.of(
                new ScoredChunk(chunk("c1", "PreloadEventListener.java", "class PreloadEventListener"), 4.0),
                new ScoredChunk(chunk("c2", "PreloadSetting.java", "class PreloadSetting"), 3.0));

        String result = enrichment.enrich("p", "Preload", 0.45, 10, true);

        assertThat(result).doesNotContain("kein valider Kontext");
        assertThat(result).contains("PreloadEventListener.java");
        assertThat(result).contains("PreloadSetting.java");
    }

    @Test
    void abortOnlyWhenBothSignalsAreEmpty() {
        dao.vectorResults = List.of();
        dao.lexicalResults = List.of();

        String result = enrichment.enrich("p", "Preload", 0.45, 10, true);

        assertThat(result).contains("kein valider Kontext");
    }

    @Test
    void vectorTopStaysFirstForConceptQueries() {
        dao.vectorResults = List.of(
                new ScoredChunk(chunk("v1", "Semantic.java", "concept"), 0.91),
                new ScoredChunk(chunk("v2", "Second.java", "concept"), 0.88));
        dao.lexicalResults = List.of(
                new ScoredChunk(chunk("l1", "Lexical.java", "concept"), 5.0),
                new ScoredChunk(chunk("v1", "Semantic.java", "concept"), 5.0));

        enrichment.enrich("p", "concept", 0.45, 10, true);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getId())
                .startsWith("v1");
    }

    @Test
    void thresholdStillFiltersWeakVectorHits() {
        dao.vectorResults = List.of(new ScoredChunk(chunk("weak", "Weak.java", "x"), 0.20));
        dao.lexicalResults = List.of();

        String result = enrichment.enrich("p", "x", 0.45, 10, true);

        assertThat(result).contains("kein valider Kontext");
    }

    @Test
    void lexicalChunksAppearThatVectorMissed() {
        dao.vectorResults = List.of(new ScoredChunk(chunk("v1", "Semantic.java", "x"), 0.91));
        dao.lexicalResults = List.of(new ScoredChunk(chunk("l1", "Lexical.java", "x"), 5.0));

        enrichment.enrich("p", "x", 0.45, 10, true);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getId())
                .containsExactlyInAnyOrder("v1", "l1");
    }

    @Test
    void lexicalWeightZeroRestoresLegacyBehaviour() {
        enrichment.lexicalWeight = 0.0;
        dao.vectorResults = List.of(new ScoredChunk(chunk("v1", "Semantic.java", "x"), 0.91));
        dao.lexicalResults = List.of(new ScoredChunk(chunk("l1", "Lexical.java", "x"), 5.0));

        enrichment.enrich("p", "x", 0.45, 10, true);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getId()).containsExactly("v1");
    }

    @Test
    void vectorWeightZeroKeepsOnlyLexicalHits() {
        enrichment.vectorWeight = 0.0;
        dao.vectorResults = List.of(new ScoredChunk(chunk("v1", "Semantic.java", "x"), 0.91));
        dao.lexicalResults = List.of(new ScoredChunk(chunk("l1", "Lexical.java", "x"), 5.0));

        enrichment.enrich("p", "x", 0.45, 10, true);

        assertThat(retriever.captured()).extracting(sc -> sc.chunk().getId()).containsExactly("l1");
    }

    @Test
    void capByFileStillApplies() {
        List<ScoredChunk> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(new ScoredChunk(chunk("l" + i, "File" + i + ".java", "x"), 5.0 - i));
        }
        dao.vectorResults = List.of();
        dao.lexicalResults = many;

        enrichment.enrich("p", "x", 0.45, 2, true);

        assertThat(retriever.captured()).hasSize(2);
    }

    @Test
    void reportLineFormatUnchanged() {
        dao.vectorResults = List.of();
        dao.lexicalResults = List.of();

        String result = enrichment.enrich("p", "x", 0.45, 10, true);

        assertThat(result).containsPattern("Effektive Similarity-Schwelle: \\d+[.,]\\d+ \\(\\d+ Fallbacks\\)\\.");
        assertThat(result).contains("kein valider Kontext");
    }

    private static class FakeDao implements PxChunkDao {
        private final PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        List<ScoredChunk> vectorResults = List.of();
        List<ScoredChunk> lexicalResults = List.of();

        FakeDao() {
            ref.setProjectName("p");
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
            return vectorResults;
        }

        @Override
        public List<ScoredChunk> searchCandidates(String query, Map<String, String> filters, int limit) {
            return lexicalResults;
        }
    }

    private static class CapturingRetriever implements GoldenRetriever {
        private List<ScoredChunk> captured = List.of();

        List<ScoredChunk> captured() {
            return captured;
        }

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks,
                                                    de.spraener.prjxp.common.retrieval.SearchParams params,
                                                    Function<String, Boolean>... contextValidators) {
            this.captured = chunks;
            StringBuilder sb = new StringBuilder();
            for (ScoredChunk sc : chunks) {
                sb.append(sc.chunk().getFile()).append('\n');
            }
            return sb;
        }

        @Override
        public List<de.spraener.prjxp.common.model.SearchHit> retrieveSearchHits(
                String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }
}
