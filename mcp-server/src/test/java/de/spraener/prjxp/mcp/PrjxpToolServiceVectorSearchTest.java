package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.enrichment.EnrichmentOutcome;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import de.spraener.prjxp.mcp.cache.SearchResultCache;
import de.spraener.prjxp.mcp.rest.QueryStatsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 02 (FilterBug): the vectorSearch threshold ladder only governs vector hits.
 * The tool passes start/floor/flags into the enrichment and formats its own header
 * from the objective {@link EnrichmentOutcome} — a BM25 hit must not be labelled
 * with a cosine threshold it never passed.
 */
@ExtendWith(MockitoExtension.class)
class PrjxpToolServiceVectorSearchTest {

    @Mock GRPromptEnrichment enrichment;
    @Mock GrepSearchService grepSearchService;
    @Mock ReaderService readerService;
    @Mock SymbolReaderService symbolReaderService;
    @Mock ProjectRegistry projectRegistry;
    @Mock FileListService fileListService;
    @Mock FileGraphService fileGraphService;
    @Mock SemanticGraphService semanticGraphService;
    @Mock de.spraener.prjxp.common.store.PxChunkDaoProvider chunkDaoProvider;
    @Mock GraphSearchService graphSearchService;
    @Mock ConfigCatalogService configCatalogService;
    @Mock QueryStatsCollector queryStats;
    @Mock SearchResultCache searchCache;

    PrjxpToolService service;

    @BeforeEach
    void setUp() {
        service = new PrjxpToolService(enrichment, grepSearchService, readerService, symbolReaderService,
                projectRegistry, fileListService, fileGraphService, semanticGraphService,
                chunkDaoProvider, graphSearchService, configCatalogService, queryStats, searchCache);
        service.vectorStartScore = 0.95;
        service.vectorMinScore = 0.80;
        service.requireVectorHit = true;
        service.growMaxResults = false;
        service.vectorFallback = "keyword";
        service.vectorConfidenceScore = 0.85;
        // Phase 03: the mock cache always misses and runs the loader, so the tests keep
        // exercising the real search path. Lenient: the unknown-project test never reaches it.
        lenient().when(searchCache.get(anyString(), any(), any(Supplier.class)))
                .thenAnswer(inv -> new SearchResultCache.Lookup<>(((Supplier<?>) inv.getArgument(2)).get(), false));
    }

    private void stubRegistry() {
        when(projectRegistry.resolve("p")).thenReturn("p");
    }

    private void stubOutcome(EnrichmentOutcome outcome) {
        when(enrichment.enrichOutcome(anyString(), anyString(), anyList(), any(SearchParams.class),
                any(Function.class), any(Function.class), anyBoolean(), any(Function.class)))
                .thenReturn(outcome);
    }

    private SearchParams capturedParams() {
        ArgumentCaptor<SearchParams> captor = ArgumentCaptor.forClass(SearchParams.class);
        verify(enrichment).enrichOutcome(anyString(), anyString(), anyList(), captor.capture(),
                any(Function.class), any(Function.class), anyBoolean(), any(Function.class));
        return captor.getValue();
    }

    @Test
    void vectorSearchPassesFloorAndRequiresVectorHit() {
        // A vector hit is present, so the normal path runs (no keyword fallback) and the
        // enrichment context is included. Phase 03: a 0-vector outcome would trigger the fallback.
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 1, 2, 0.90, 0.80, 0, false));

        String out = service.vectorSearch("q", "p", null, null, null, null, null);

        SearchParams params = capturedParams();
        assertThat(params.getMinScore()).isEqualTo(0.95);
        assertThat(params.getMinScoreFloor()).isEqualTo(0.80);
        assertThat(params.isRequireVectorHit()).isTrue();
        assertThat(params.isGrowMaxResult()).isFalse();
        assertThat(params.isLowerThresholdFirst()).isTrue();
        assertThat(out).contains("Vektor-Treffer: 1");
        assertThat(out).contains("Lexikal-Treffer: 2");
        assertThat(out).contains("Schwelle 0.95→0.80 (0 Fallbacks)");
        assertThat(out).contains("Hinweis: Treffer mit score > 1.0 sind lexikalisch (BM25), nicht Cosine.");
        assertThat(out).contains("ctx");

        // 1 vector + 2 lexical hits, recorded once
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(3), eq(false));
    }

    @Test
    void similarityIsClampedToFloor() {
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 1, 0, 0.90, 0.80, 0, false));

        service.vectorSearch("q", "p", 0.5, null, null, null, null);

        assertThat(capturedParams().getMinScore()).isEqualTo(0.80);
    }

    @Test
    void similarityAboveOneIsClampedToOne() {
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 1, 0, 0.90, 1.0, 0, false));

        service.vectorSearch("q", "p", 1.5, null, null, null, null);

        assertThat(capturedParams().getMinScore()).isEqualTo(1.0);
    }

    @Test
    void headerShowsVectorAndLexicalCounts() {
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 3, 1, 0.95, 0.95, 0, false));

        String out = service.vectorSearch("q", "p", null, null, null, null, null);

        assertThat(out).contains("Vektor-Treffer: 3 (best 0.95) | Lexikal-Treffer: 1 | Schwelle 0.95→0.95 (0 Fallbacks)");
    }

    @Test
    void unknownProjectIsNotRecorded() {
        doThrow(new UnknownProjectException("nope", List.of("alpha")))
                .when(projectRegistry).ensureSearchable("nope");

        String out = service.vectorSearch("q", "nope", null, null, null, null, null);

        assertThat(out).startsWith("ERROR:");
        verifyNoInteractions(queryStats);   // no search was executed
        verifyNoInteractions(searchCache);  // validation stays in front of the cache
    }

    // ---- Phase 03: cache integration ----

    @Test
    void secondIdenticalCallHitsTheCache() {
        // The mock cache misses on the first call (loader runs) and hits on the second:
        // the enrichment must run exactly once, and the stats must see one miss + one hit.
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 1, 2, 0.90, 0.80, 0, false));
        when(searchCache.get(anyString(), any(), any(Supplier.class)))
                .thenAnswer(inv -> new SearchResultCache.Lookup<>(((Supplier<?>) inv.getArgument(2)).get(), false))
                .thenAnswer(inv -> new SearchResultCache.Lookup<>(new PrjxpToolService.ToolSearchResult("cached", 3), true));

        String first = service.vectorSearch("q", "p", null, null, null, null, null);
        String second = service.vectorSearch("q", "p", null, null, null, null, null);

        assertThat(first).contains("ctx");
        assertThat(second).isEqualTo("cached");
        verify(enrichment, times(1)).enrichOutcome(anyString(), anyString(), anyList(), any(SearchParams.class),
                any(Function.class), any(Function.class), anyBoolean(), any(Function.class));
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(3), eq(false));
        verify(queryStats, times(1)).recordQuery(eq("q"), anyLong(), eq(3), eq(true));
    }

    // ---- Phase 03: lexical keyword fallback ----

    private static final String ABORT =
            "Es konnte kein valider Kontext erstellt werden! Effektive Similarity-Schwelle: 0.80 (3 Fallbacks).";

    /** No vector hit — the Phase 03 fallback trigger (the lexical count is irrelevant to it). */
    private void stubDeadEnd() {
        stubRegistry();
        stubOutcome(new EnrichmentOutcome(ABORT, 0, 0, 0.0, 0.80, 3, true));
    }

    @Test
    void fallbackTriggersWhenVectorEmpty() {
        stubDeadEnd();
        when(grepSearchService.searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new GrepResult(List.of(
                        new SearchHit("de.ex.A", 0.0, "src/A.java", 42, 42,
                                "public Stream<PxChunk> chunk(File f) {", "grep", Map.of())), 1, null));

        String out = service.vectorSearch("How does the chunker work", "p", null, null, null, null, null);

        assertThat(out).contains("Vektor-Treffer: 0 | Lexikal-Treffer: 0");
        assertThat(out).contains("Lexikalischer Fallback (Terme: how, does, chunker, work):");
        assertThat(out).contains("src/A.java:42: public Stream<PxChunk> chunk(File f) {");
        assertThat(out).contains("readFile(\"src/A.java\", offset=37, limit=40)");
        assertThat(out).contains("readChunks(ids=\"de.ex.A\")");

        // the keyword fallback is a regular return: the query is recorded (0 vector + 0 lexical)
        verify(queryStats, times(1)).recordQuery(eq("How does the chunker work"), anyLong(), eq(0), eq(false));
    }

    @Test
    void lexicalHitsWithoutVectorHitsTriggerTheFallback() {
        // Phase 03: the trigger is "no vector hit" — lexical hits alone do NOT keep the pointer
        // output. 0 vector + 2 lexical still runs the keyword fallback (file:line hits).
        stubRegistry();
        stubOutcome(new EnrichmentOutcome(ABORT, 0, 2, 0.0, 0.80, 3, true));
        when(grepSearchService.searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new GrepResult(List.of(
                        new SearchHit("de.ex.A", 0.0, "src/A.java", 42, 42,
                                "public Stream<PxChunk> chunk(File f) {", "grep", Map.of())), 1, null));

        String out = service.vectorSearch("Vorgang service process", "p", null, null, null, null, null);

        verify(grepSearchService).searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThat(out).contains("Vektor-Treffer: 0 | Lexikal-Treffer: 2");
        assertThat(out).contains("Lexikalischer Fallback");
        assertThat(out).contains("src/A.java:42:");   // datei:zeile
    }

    @Test
    void fallbackOffReturnsAbortMessage() {
        stubDeadEnd();
        service.vectorFallback = "off";

        String out = service.vectorSearch("q", "p", null, null, null, null, null);

        assertThat(out).contains("kein valider Kontext");
        assertThat(out).doesNotContain("Lexikalischer Fallback");
        verify(grepSearchService, never())
                .searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void fallbackRespectsSourceType() {
        stubDeadEnd();
        when(grepSearchService.searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new GrepResult(List.of(), 0, "Keine Keyword-Treffer für: chunker, pipeline"));

        service.vectorSearch("chunker pipeline", "p", null, null, "code", null, null);

        // sourceType=code must reach the keyword search as a negated doc-mime filter
        verify(grepSearchService).searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf")));
    }

    @Test
    void fallbackWithoutHitsReportsTermsAndDeadEndHint() {
        stubDeadEnd();
        when(grepSearchService.searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new GrepResult(List.of(), 0, null));

        String out = service.vectorSearch("Vorgang service process", "p", null, null, null, null, null);

        assertThat(out).contains("Keine Keyword-Treffer für: vorgang, service, process");
        assertThat(out).contains("Keine Code-Treffer. Mit sourceType=all wiederholen");
    }

    // ---- Phase 03b: confidence gate ----

    @Test
    void weakVectorHitBelowConfidenceTriggersFallback() {
        // 27 vector hits at the floor, best 0.81 < confidence 0.85: the gate treats the
        // weak vector channel like a dead end and runs the keyword fallback.
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 27, 40, 0.81, 0.80, 3, false));
        when(grepSearchService.searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(new GrepResult(List.of(
                        new SearchHit("de.ex.A", 0.0, "src/A.java", 42, 42,
                                "public Stream<PxChunk> chunk(File f) {", "grep", Map.of())), 1, null));

        String out = service.vectorSearch("Vorgang service process", "p", null, null, null, null, null);

        verify(grepSearchService).searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThat(out).contains("Vektor-Treffer: 27 (best 0.81)");
        assertThat(out).contains("Lexikalischer Fallback");
    }

    @Test
    void strongVectorHitKeepsPointerOutput() {
        // best 0.88 >= confidence 0.85: semantically carried, no fallback, pointer output.
        stubRegistry();
        stubOutcome(new EnrichmentOutcome("ctx", 5, 2, 0.88, 0.80, 0, false));

        String out = service.vectorSearch("q", "p", null, null, null, null, null);

        verify(grepSearchService, never()).searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThat(out).contains("Vektor-Treffer: 5 (best 0.88)");
        assertThat(out).contains("ctx");
        assertThat(out).doesNotContain("Lexikalischer Fallback");
    }

    @Test
    void confidenceZeroDisablesGate() {
        // confidence-score=0 restores the pure Phase 03 behaviour: only "no vector hit"
        // triggers the fallback, a weak best score does not.
        stubRegistry();
        service.vectorConfidenceScore = 0.0;
        stubOutcome(new EnrichmentOutcome("ctx", 27, 40, 0.81, 0.80, 3, false));

        String out = service.vectorSearch("Vorgang service process", "p", null, null, null, null, null);

        verify(grepSearchService, never()).searchKeywords(anyList(), anyString(), any(), anyInt(), anyBoolean(), any());
        assertThat(out).contains("Vektor-Treffer: 27 (best 0.81)");
        assertThat(out).contains("ctx");
    }
}
