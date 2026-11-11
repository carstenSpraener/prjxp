package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import de.spraener.prjxp.gldrtrvr.chunks.LeidenWeightRankingStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 02 (Graphify-Tool-V2): the default dependency cap is 5. Verified via the retrieved
 * prompt: a class frame whose dependency chunk holds 8 entries renders at most 5 of them.
 * <p>
 * Plain unit test (no Spring context): the module ships no {@code @SpringBootConfiguration}
 * and the legacy {@code prjxp-enriched.jsonl} fixture does not exist, so the previous
 * {@code @SpringBootTest} setup could never start a context with active tests.
 */
class JavaRetrieverTest {

    private PxChunkDao inMemoryDao(PxChunk... chunks) {
        Map<String, List<PxChunk>> db = new HashMap<>();
        for (PxChunk c : chunks) {
            db.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(c);
        }
        PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        ref.setDefault(true);
        return new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return ref;
            }

            @Override
            public List<PxChunk> findById(String id) {
                return db.getOrDefault(id, List.of());
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
                return db.values().stream().flatMap(List::stream);
            }
        };
    }

    private JavaRetriever retrieverWith(PxChunk... chunks) {
        PxChunkDaoProvider provider = new PxChunkDaoProvider(List.of(inMemoryDao(chunks)));
        List<ChunkRankingStrategy> strategies = List.of(new LeidenWeightRankingStrategy());
        ChunkRankingService rankingService = new ChunkRankingService(strategies, new GraphRerankConfig());
        return new JavaRetriever(provider, rankingService);
    }

    @Test
    void defaultMaxDependencyEntriesIsFive() {
        // leiden_weight > 0 so the chunk gets a non-zero root rank and is not skipped
        PxChunk clazz = PxChunk.create(
                c -> c.setId("de.test.PointerClass"),
                c -> c.setFile("/abs/de/test/PointerClass.java"),
                c -> c.setContent("class PointerClass {}"),
                c -> c.setPart(0),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("java_code_section", "classFrame"),
                c -> c.getMetadata().put("leiden_weight", "1.0"));
        StringBuilder deps = new StringBuilder("## Dependencies of de.test.PointerClass:\n\n**Outgoing**\n");
        for (int i = 0; i < 8; i++) {
            deps.append("  * de.test.dep.D").append(i).append('\n');
        }
        PxChunk dep = PxChunk.create(
                c -> c.setId("de.test.PointerClass.dependencies"),
                c -> c.setContent(deps.toString()),
                c -> c.setPart(0),
                c -> c.setTotal(1));

        JavaRetriever retriever = retrieverWith(clazz, dep);
        String prompt = retriever.buildPromptForFindings(
                "default", List.of(new ScoredChunk(clazz, 1.0)), new SearchParams(20, 0.85)).toString();

        long entries = prompt.lines().filter(l -> l.startsWith("  * ")).count();
        assertThat(entries).as("dependency section rendered").isGreaterThan(0);
        assertThat(entries).as("at most the default cap of 5 entries").isLessThanOrEqualTo(5);
    }
}
