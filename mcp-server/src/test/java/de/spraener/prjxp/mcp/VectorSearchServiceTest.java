package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.mcp.cache.SearchResultCache;
import de.spraener.prjxp.mcp.rest.QueryStatsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    ProjectRegistry projectRegistry;

    @Mock
    SearchCapabilitiesRegistry registry;

    @Spy
    GraphRerankConfig graphRerankConfig = new GraphRerankConfig();

    @Mock
    QueryStatsCollector queryStats;

    @Mock
    SearchResultCache searchCache;

    @InjectMocks
    VectorSearchService service;

    /**
     * Phase 03: the mock cache always misses and runs the loader, so the existing tests keep
     * exercising the real search path. Lenient: tests that return before the cache (no store,
     * unknown project) never touch it.
     */
    @BeforeEach
    void setUp() {
        lenient().when(searchCache.get(anyString(), any(), any(Supplier.class)))
                .thenAnswer(inv -> new SearchResultCache.Lookup<>(((Supplier<?>) inv.getArgument(2)).get(), false));
    }

    private LanguageCapability javaCapability() {
        return new LanguageCapability(
                "java",
                List.of("text/x-java-code"),
                List.of("method", "classFrame"),
                List.of(),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    /** The FULLTEXT exclusion filter that every vector search carries (fully-qualified field name). */
    private static final Map<String, String> FULLTEXT_FILTER =
            Map.of(PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER);

    @Test
    void passesQueryProjectLanguageAndLimitToDao() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("How does chunking work?", "myproj", null, 10);

        verify(dao).searchVector(
                eq("How does chunking work?"),
                eq(FULLTEXT_FILTER),
                eq(10));
    }

    @Test
    void languageFromRegistryBecomesMimeFilter() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search("q", "myproj", "java", 10);

        verify(dao).searchVector(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code",
                        PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER)),
                eq(10));
    }

    @Test
    void unknownLanguageStillCarriesFulltextFilter() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "cobol", 10);

        verify(dao).searchVector(
                eq("q"),
                eq(FULLTEXT_FILTER),
                eq(10));
    }

    @Test
    void defaultProjectUsesDefaultStore() {
        when(projectRegistry.resolve("default")).thenReturn("default");
        when(provider.get("default")).thenReturn(Optional.empty());

        service.search("q", "default", null, 10);

        verify(provider).get("default");
        verifyNoInteractions(queryStats);   // no store, no search: nothing to record
    }

    @Test
    void blankProjectResolvesToActiveProjectWithoutSilentFallback() {
        when(projectRegistry.resolve("   ")).thenReturn("cwd");
        when(provider.get("cwd")).thenReturn(Optional.empty());

        service.search("q", "   ", null, 10);

        verify(provider).get("cwd");
        verify(provider, never()).get("default");   // the silent cross-project fallback is gone
    }

    @Test
    void unknownProjectThrowsUnknownProjectException() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        assertThatThrownBy(() -> service.search("q", "nope", null, 10))
                .isInstanceOf(UnknownProjectException.class);

        verifyNoInteractions(provider);
        verifyNoInteractions(dao);
        verifyNoInteractions(queryStats);   // unknown project: no search was executed
    }

    @Test
    void resultsMappedToSearchHitWithVectorSource() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setFromLine("10");
            c.setToLine("25");
            c.setContent("x".repeat(300));
        });
        chunk.getMetadata().put("java_code_section", "method");

        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.87)));

        List<SearchHit> hits = service.search("How does chunking work?", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("chunk-1");
        assertThat(hit.score()).isEqualTo(0.87);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isEqualTo(10);
        assertThat(hit.lineTo()).isEqualTo(25);
        assertThat(hit.source()).isEqualTo("vector");
        assertThat(hit.snippet()).hasSize(240);
        assertThat(hit.metadata()).containsEntry("java_code_section", "method");

        verify(queryStats, times(1)).recordQuery(eq("How does chunking work?"), anyLong(), eq(1), eq(false));
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no vector store"));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
        // Phase 03: the loader catches the unsupported store and returns an empty list,
        // which is recorded (and cached) as a 0-hit query.
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(0), eq(false));
    }

    @Test
    void secondIdenticalCallHitsTheCache() {
        // Phase 03: with a REAL cache, the second identical call must not hit the dao again
        // and must be recorded with fromCache=true.
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setContent("x".repeat(300));
        });
        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.87)));

        VectorSearchService cached = new VectorSearchService(provider, registry, projectRegistry,
                graphRerankConfig, queryStats, new SearchResultCache(512, 60));

        List<SearchHit> first = cached.search("q", "myproj", null, 10);
        List<SearchHit> second = cached.search("q", "myproj", null, 10);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).chunkId()).isEqualTo("chunk-1");
        verify(dao, times(1)).searchVector(anyString(), anyMap(), anyInt());   // the loader ran once
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(1), eq(false));
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(1), eq(true));
    }

    @Test
    void centralChunkRanksHigherThanPeripheralWithSameSimilarity() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk central = PxChunk.create(c -> {
            c.setId("central");
            c.setContent("central content");
            c.getMetadata().put("leiden_weight", "1.0");
        });
        PxChunk peripheral = PxChunk.create(c -> {
            c.setId("peripheral");
            c.setContent("peripheral content");
            c.getMetadata().put("leiden_weight", "0.0");
        });

        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(central, 0.9), new ScoredChunk(peripheral, 0.9)));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).hasSize(2);
        assertThat(scoreOf(hits, "central")).isCloseTo(0.9, offset(1e-9));
        assertThat(scoreOf(hits, "peripheral")).isCloseTo(0.72, offset(1e-9));   // 0.9 * floor(0.8)
    }

    @Test
    void missingWeightKeepsRawSimilarity() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setContent("x".repeat(300));
        });

        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.87)));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(0.87);   // weight 1.0 = neutral factor
    }

    @Test
    void weightRerankAlsoAppliesToChunkWithLanguageSection() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setContent("x".repeat(300));
            c.getMetadata().put("java_code_section", "method");
            c.getMetadata().put("leiden_weight", "0.5");
        });

        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.9)));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isCloseTo(0.81, offset(1e-9));   // 0.9 * (0.8 + 0.2 * 0.5)
    }

    private static double scoreOf(List<SearchHit> hits, String chunkId) {
        return hits.stream()
                .filter(h -> chunkId.equals(h.chunkId()))
                .findFirst()
                .orElseThrow()
                .score();
    }
}
