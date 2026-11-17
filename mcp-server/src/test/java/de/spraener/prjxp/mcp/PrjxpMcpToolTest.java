package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrjxpToolServiceTest {

    /** The vector-filter map a code-scoped search (the default) must receive. */
    private static final Map<String, String> CODE_FILTER =
            Map.of(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf");

    @Mock
    private GRPromptEnrichment enrichment;
    @Mock
    private GrepSearchService grepSearchService;
    @Mock
    private ReaderService readerService;
    @Mock
    private SymbolReaderService symbolReaderService;
    @Mock
    private ProjectRegistry projectRegistry;
    @Mock
    private FileListService fileListService;
    @Mock
    private FileGraphService fileGraphService;
    @Mock
    private SemanticGraphService semanticGraphService;
    @Mock
    private de.spraener.prjxp.common.store.PxChunkDaoProvider chunkDaoProvider;
    @Mock
    private GraphSearchService graphSearchService;
    @Mock
    private ConfigCatalogService configCatalogService;

    private PrjxpToolService tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpToolService(enrichment, grepSearchService, readerService, symbolReaderService,
                projectRegistry, fileListService, fileGraphService, semanticGraphService, chunkDaoProvider,
                graphSearchService, configCatalogService);
    }

    @Test
    void readFilePassesProjectThrough() {
        when(readerService.read("src/Foo.java", "myproj", null, null, false))
                .thenReturn(FileView.error("src/Foo.java", "no index"));

        FileView view = tool.readFile("src/Foo.java", "myproj", null, null, null);

        assertThat(view.error()).isEqualTo("no index");
        verify(readerService).read("src/Foo.java", "myproj", null, null, false);

        // a blank 'file' must short-circuit to an error view without touching the service
        FileView err = tool.readFile("", "prj", 1, 5, null);

        assertThat(err.content()).isNull();
        assertThat(err.error()).contains("file");
        verifyNoMoreInteractions(readerService);
    }

    @Test
    void readFileDefaultsFullToFalse() {
        when(readerService.read("src/Foo.java", "myproj", null, null, false))
                .thenReturn(new FileView("src/Foo.java", "java", 1, "class Foo {}", false, 1, 1, null, null));

        tool.readFile("src/Foo.java", "myproj", null, null, null);

        verify(readerService).read("src/Foo.java", "myproj", null, null, false);
    }

    @Test
    void readFileFullTrueBypassesTheLineDefault() {
        when(readerService.read("src/Foo.java", "myproj", null, null, true))
                .thenReturn(new FileView("src/Foo.java", "java", 1, "class Foo {}", false, 1, 1, null, null));

        tool.readFile("src/Foo.java", "myproj", null, null, true);

        verify(readerService).read("src/Foo.java", "myproj", null, null, true);
    }

    @Test
    void vectorSearchUnknownProjectReturnsErrorString() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        String result = tool.vectorSearch("How does chunking work?", "nope", null, null, null, null, null);

        assertThat(result).startsWith("ERROR:").contains("nope").contains("alpha");
        verifyNoInteractions(enrichment);
    }

    @Test
    void vectorSearchResolvesProjectViaRegistry() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "How does chunking work?", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER))
                .thenReturn("Relevante Information in 'myproj':\nchunk content");

        String result = tool.vectorSearch("How does chunking work?", "myproj", null, null, null, null, null);

        assertThat(result).contains("chunk content");
        verify(enrichment).enrich("myproj", "How does chunking work?", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchDefaultProjectResolvesViaRegistry() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        String result = tool.vectorSearch("q", "default", null, null, null, null, null);

        assertThat(result).contains("ctx");
        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchDefaultsIncludeTestsToFalse() {
        // Semantic discovery: tests are noise — null includeTests must default to false
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchPassesExplicitIncludeTestsThrough() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, true, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, true, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, true, CODE_FILTER);
    }

    // ---- the pointer-only contract (no contentLevel knob) ----

    @Test
    void vectorSearchAlwaysUsesPointers() {
        // the MCP vectorSearch is pinned to the cheap POINTERS path — there is no
        // contentLevel parameter to escalate to SKELETON/FULL
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    // ---- the sourceType knob (cheap path) ----

    @Test
    void vectorSearchDefaultsToCodeSourceType() {
        // null sourceType must normalise to the code filter (excludes prose docs)
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false,
                Map.of(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf"));
    }

    @Test
    void vectorSearchExplicitSourceTypeDocsWinsOverDefault() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        Map<String, String> docsFilter = Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, "docs", null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter);
    }

    @Test
    void vectorSearchDefaultsMaxResultsToTen() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void emptyCodeSearchAppendsSourceTypeHint() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        String deadEnd = "Es konnte kein valider Kontext erstellt werden! Effektive Similarity-Schwelle: 0.50 (3 Fallbacks).";

        // positive: sourceType=null -> code -> the dead-end hint is appended
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn(deadEnd);
        String codeResult = tool.vectorSearch("q", "myproj", null, null, null, null, null);
        assertThat(codeResult).contains("sourceType=all");

        // negative: sourceType=docs -> the hint is NOT appended
        Map<String, String> docsFilter = Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter)).thenReturn(deadEnd);
        String docsResult = tool.vectorSearch("q", "myproj", null, null, "docs", null, null);
        assertThat(docsResult).doesNotContain("sourceType=all");
    }

    @Test
    void grepDefaultsIncludeTestsToTrue() {
        // Exact search is deliberate intent: null includeTests must default to true;
        // the level is pinned to NAMES (grep -l) — there is no contentLevel knob
        when(grepSearchService.searchDetailed("q", "myproj", null, 30, true, ContentLevel.NAMES))
                .thenReturn(GrepResult.of(List.of(), 0));

        tool.grep("q", "myproj", null, null, null);

        verify(grepSearchService).searchDetailed("q", "myproj", null, 30, true, ContentLevel.NAMES);
    }

    @Test
    void grepPassesExplicitIncludeTestsThrough() {
        when(grepSearchService.searchDetailed("q", "myproj", null, 30, false, ContentLevel.NAMES))
                .thenReturn(GrepResult.of(List.of(), 0));

        tool.grep("q", "myproj", null, null, false);

        verify(grepSearchService).searchDetailed("q", "myproj", null, 30, false, ContentLevel.NAMES);
    }

    @Test
    void grepReturnsNamesText() {
        SearchHit hit = new SearchHit("9  Foo.java", 9.0, "Foo.java", null, null,
                "9  Foo.java", "grep", Map.of());
        when(grepSearchService.searchDetailed("q", "myproj", null, 30, true, ContentLevel.NAMES))
                .thenReturn(GrepResult.of(List.of(hit), 1));

        assertThat(tool.grep("q", "myproj", null, null, null)).isEqualTo("9  Foo.java\n");
    }

    @Test
    void grepWithoutHitsHintsAtExactTokenSemantics() {
        // No silent dead end: a prefix of a symbol name finds nothing (whole-token match),
        // so the answer must point at the full name or listFiles
        when(grepSearchService.searchDetailed("ChunkRanking", "myproj", null, 30, true, ContentLevel.NAMES))
                .thenReturn(GrepResult.of(List.of(), 0));

        String result = tool.grep("ChunkRanking", "myproj", null, null, null);

        assertThat(result).contains("No matches").contains("listFiles(\"*ChunkRanking*\")");
    }

    // ---- result caps (the MCP path must not emit 17k/20k-char answers) ----

    @Test
    void vectorSearchCapsResultAtSearchCap() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER))
                .thenReturn("x".repeat(20_000));

        String result = tool.vectorSearch("q", "myproj", null, null, null, null, null);

        assertThat(result.length()).isLessThan(20_000);
        assertThat(result).contains("gekürzt");
        assertThat(result).contains("tokenBudget");
    }

    @Test
    void grepCapsResultAtSearchCap() {
        // 30 files with long paths -> well over the 8k search cap
        List<SearchHit> hits = IntStream.range(0, 30)
                .mapToObj(i -> {
                    String path = "src/".repeat(100) + "File" + i + ".java";
                    return new SearchHit("1  " + path, 1.0, path, null, null, "1  " + path, "grep", Map.of());
                })
                .toList();
        when(grepSearchService.searchDetailed("q", "myproj", null, 30, true, ContentLevel.NAMES))
                .thenReturn(GrepResult.of(hits, 30));

        String result = tool.grep("q", "myproj", null, null, null);

        assertThat(result.length()).isLessThan(20_000);
        assertThat(result).contains("gekürzt");
    }

    @Test
    void readFileCapsContentAtReaderCap() {
        String bigContent = "line\n".repeat(3000);   // 15000 chars
        when(readerService.read("src/Foo.java", "myproj", null, null, false))
                .thenReturn(new FileView("src/Foo.java", "java", 1, bigContent, false, 3000, 200, null, null));

        FileView view = tool.readFile("src/Foo.java", "myproj", null, null, null);

        assertThat(view.truncated()).isTrue();
        assertThat(view.content().length()).isLessThan(15_000);
        assertThat(view.content()).contains("gekürzt");
        assertThat(view.totalLines()).isEqualTo(3000);
    }

    @Test
    void readFileFullTrueUsesTheLargerButBoundedCap() {
        // full=true is an escape hatch, not a blank cheque: it gets more room than a normal
        // read but is still capped, so one call cannot blow the context window.
        String bigContent = "line\n".repeat(6000);   // 30000 chars > READER_FULL (20000)
        when(readerService.read("src/Foo.java", "myproj", null, null, true))
                .thenReturn(new FileView("src/Foo.java", "java", 1, bigContent, false, 6000, 6000, null, null));

        FileView view = tool.readFile("src/Foo.java", "myproj", null, null, true);

        assertThat(view.truncated()).isTrue();
        assertThat(view.content().length()).isLessThan(30_000);
        assertThat(view.content()).contains("gekürzt");
        assertThat(view.totalLines()).isEqualTo(6000);
    }

    @Test
    void readBySignatureCapsBodiesAtReaderCap() {
        String bigBody = "code\n".repeat(3000);   // 15000 chars
        MethodView match = new MethodView("com.example.Foo#bar", "Foo.java", 5, 9, "/** doc */", bigBody);
        when(symbolReaderService.readBySignature("bar", null, "myproj"))
                .thenReturn(new SymbolReadResult(List.of(match), List.of(), null));

        SymbolReadResult result = tool.readBySignature("bar", null, "myproj");

        assertThat(result.matches().get(0).body().length()).isLessThan(15_000);
        assertThat(result.matches().get(0).body()).contains("gekürzt");
        assertThat(result.matches().get(0).javadoc()).isEqualTo("/** doc */");
    }

    @Test
    void listFilesCapsResultAtSearchCap() {
        // 500 files with long paths -> well over the 8k search cap
        List<String> files = IntStream.range(0, 500)
                .mapToObj(i -> "src/".repeat(100) + "File" + i + ".java")
                .toList();
        when(fileListService.list("*", "myproj", 500)).thenReturn(new FileListResult(files, true));

        String result = tool.listFiles("*", "myproj", 500);

        assertThat(result.length()).isLessThan(20_000);
        assertThat(result).contains("gekürzt");
    }

    @Test
    void graphNodeCapsResultAtSearchCap() {
        // 500 outgoing edges with long ids -> well over the 8k search cap
        List<GraphNeighbor> outgoing = IntStream.range(0, 500)
                .mapToObj(i -> new GraphNeighbor(EdgeType.IMPORT,
                        "com.example." + "x".repeat(30) + ".A" + i,
                        "src/" + "x".repeat(30) + "/A" + i + ".java", false))
                .toList();
        when(fileGraphService.graphNode("demo.A", "myproj", 500, false, null, ContentLevel.FULL))
                .thenReturn(new GraphView("myproj", null, "demo.A", null, null,
                        outgoing, List.of(), List.of(), null, 500, 0, null, null));

        String result = tool.graphNode("demo.A", "myproj", 500, false, null, null, null);

        assertThat(result.length()).isLessThan(20_000);
        assertThat(result).contains("gekürzt");
    }

    @Test
    void graphFileCapsResultAtSearchCap() {
        // 500 incoming edges with long ids -> well over the 8k search cap
        List<GraphNeighbor> incoming = IntStream.range(0, 500)
                .mapToObj(i -> new GraphNeighbor(EdgeType.CALL,
                        "com.example." + "y".repeat(30) + ".B" + i,
                        "src/" + "y".repeat(30) + "/B" + i + ".java", false))
                .toList();
        when(fileGraphService.graphFile("src/A.java", "myproj", 500, false, null, ContentLevel.FULL))
                .thenReturn(new GraphView("myproj", "src/A.java", "demo.A", null, null,
                        List.of(), incoming, List.of(), null, 0, 500, null, null));

        String result = tool.graphFile("src/A.java", "myproj", 500, false, null, null, null);

        assertThat(result.length()).isLessThan(20_000);
        assertThat(result).contains("gekürzt");
    }

    @Test
    void listProjectsFiltersToSearchableOnly() {
        when(projectRegistry.projectInfos()).thenReturn(List.of(
                new ProjectInfo("alpha", "READY", null),
                new ProjectInfo("beta", "EMBEDDING", null)));
        when(projectRegistry.isSearchable("alpha")).thenReturn(true);
        when(projectRegistry.isSearchable("beta")).thenReturn(false);

        assertThat(tool.listProjects()).containsExactly(new ProjectSummary("alpha", null));
    }

    @Test
    void listProjectsCarriesLiveUpdateWatermark() {
        when(projectRegistry.projectInfos()).thenReturn(List.of(
                new ProjectInfo("alpha", "READY", null, null, 10L, 10L, 3,
                        null, null, null, null, null, false, "IDLE", 0, null, null,
                        new ProjectInfo.LiveUpdateInfo("IDLE", 1700000000000L, null, null),
                        null, null, null, null),
                new ProjectInfo("snapshot", "READY", null)));
        when(projectRegistry.isSearchable("alpha")).thenReturn(true);
        when(projectRegistry.isSearchable("snapshot")).thenReturn(true);

        assertThat(tool.listProjects()).containsExactly(
                new ProjectSummary("alpha", 1700000000000L),
                new ProjectSummary("snapshot", null));
    }

    @Test
    void listFilesDefaultsLimit() {
        when(fileListService.list(null, "myproj", 50)).thenReturn(new FileListResult(List.of("a.java"), false));

        FileListResult result = tool.listFilesResult(null, "myproj", null);

        assertThat(result.files()).containsExactly("a.java");
        verify(fileListService).list(null, "myproj", 50);
    }

    @Test
    void listFilesClampsLimitToMax() {
        when(fileListService.list("src", "myproj", 500)).thenReturn(new FileListResult(List.of(), false));

        tool.listFilesResult("src", "myproj", 10000);

        verify(fileListService).list("src", "myproj", 500);
    }

    @Test
    void graphFilePassesProjectThrough() {
        when(fileGraphService.graphFile("src/A.java", "myproj", 5, true, null, ContentLevel.FULL))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        GraphView view = tool.graphFileView("  src/A.java  ", "myproj", 5, true, null, null);

        assertThat(view.error()).isEqualTo("Graph nicht verfügbar, bitte reindexieren");
        verify(fileGraphService).graphFile("src/A.java", "myproj", 5, true, null, ContentLevel.FULL);
    }

    @Test
    void graphFilePassesOffsetThrough() {
        when(fileGraphService.graphFile("src/A.java", "myproj", 500, false, 30, ContentLevel.FULL))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        tool.graphFileView("src/A.java", "myproj", 500, false, 30, null);

        verify(fileGraphService).graphFile("src/A.java", "myproj", 500, false, 30, ContentLevel.FULL);
    }

    @Test
    void graphFilePassesContentLevelThrough() {
        // Efficiency measure 5: the contentLevel string is parsed to the enum for the service
        when(fileGraphService.graphFile("src/A.java", "myproj", null, null, null, ContentLevel.AGGREGATED))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        tool.graphFileView("src/A.java", "myproj", null, null, null, "AGGREGATED");

        verify(fileGraphService).graphFile("src/A.java", "myproj", null, null, null, ContentLevel.AGGREGATED);
    }

    @Test
    void graphNodePassesProjectThrough() {
        when(fileGraphService.graphNode("demo.A", "myproj", null, null, null, ContentLevel.FULL))
                .thenReturn(GraphView.unavailable("Node nicht gefunden: demo.A"));

        GraphView view = tool.graphNodeView("demo.A", "myproj", null, null, null, null);

        assertThat(view.error()).contains("demo.A");
        verify(fileGraphService).graphNode("demo.A", "myproj", null, null, null, ContentLevel.FULL);
    }

    @Test
    void graphNodePassesOffsetThrough() {
        when(fileGraphService.graphNode("demo.A", "myproj", 500, false, 100, ContentLevel.FULL))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        tool.graphNodeView("demo.A", "myproj", 500, false, 100, null);

        verify(fileGraphService).graphNode("demo.A", "myproj", 500, false, 100, ContentLevel.FULL);
    }

    @Test
    void graphNodePassesContentLevelThrough() {
        when(fileGraphService.graphNode("demo.A", "myproj", null, null, null, ContentLevel.AGGREGATED))
                .thenReturn(GraphView.unavailable("Node nicht gefunden: demo.A"));

        tool.graphNodeView("demo.A", "myproj", null, null, null, "AGGREGATED");

        verify(fileGraphService).graphNode("demo.A", "myproj", null, null, null, ContentLevel.AGGREGATED);
    }

    @Test
    void graphNodeBlankContentLevelParsesToFull() {
        when(fileGraphService.graphNode("demo.A", "myproj", null, null, null, ContentLevel.FULL))
                .thenReturn(GraphView.unavailable("Node nicht gefunden: demo.A"));

        tool.graphNodeView("demo.A", "myproj", null, null, null, "  ");

        verify(fileGraphService).graphNode("demo.A", "myproj", null, null, null, ContentLevel.FULL);
    }

    @Test
    void graphFileRequiresFile() {
        GraphView view = tool.graphFileView("", "demo", null, null, null, null);

        assertThat(view.error()).isEqualTo("Parameter 'file' ist erforderlich.");
        verifyNoInteractions(fileGraphService);
    }

    @Test
    void vectorSearchHonoursAnExplicitTokenBudget() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER))
                .thenReturn("x".repeat(20_000));

        String result = tool.vectorSearch("q", "myproj", null, null, null, null, 100);

        // 100 tokens ~ 300 chars + hint
        assertThat(result.length()).isLessThan(600);
        assertThat(result).contains("gekürzt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphSearchDelegatesToTheService() {
        GraphSearchView view = new GraphSearchView("myproj", List.of("demo.A"),
                List.of(new GraphSearchView.GraphSearchNode("demo.A", "src/A.java", false, null, null)),
                List.of(), null, 1, 0, false);
        when(graphSearchService.search("myproj", "demo.A", "bfs", 3, 2000, java.util.Set.of(), 3))
                .thenReturn(view);

        String result = tool.graphSearch("demo.A", "myproj", null, null, null, null, null);

        assertThat(result).contains("demo.A").contains("Seeds: demo.A");
    }

    @Test
    void listConfigDelegatesToTheService() {
        when(configCatalogService.list("myproj")).thenReturn(new ConfigCatalog("myproj",
                List.of(new ConfigProperty("prjxp.a.b", "String", "x", "desc", "application.yaml")), null));

        String result = tool.listConfig("myproj", null);

        assertThat(result).contains("prjxp.a.b").contains("desc");
    }

    @Test
    void graphClusterDelegatesToFileGraphService() {
        ClusterView view = new ClusterView("myproj", "c6", 3,
                List.of(new ClusterMember("demo.A", "src/A.java", 0.9, 2, 3)), null);
        when(fileGraphService.graphCluster("c6", "myproj", 5)).thenReturn(view);

        ClusterView result = tool.graphCluster("c6", "myproj", 5);

        assertThat(result).isSameAs(view);
        verify(fileGraphService).graphCluster("c6", "myproj", 5);
    }

    @Test
    void graphClusterOverviewDelegatesToFileGraphService() {
        ClusterOverviewView view = new ClusterOverviewView("myproj", 3,
                List.of(new ClusterSummary("c0", 3, List.of("demo.A", "demo.B", "demo.C"))), null);
        when(fileGraphService.graphClusterOverview("myproj", 5)).thenReturn(view);

        ClusterOverviewView result = tool.graphClusterOverview("myproj", 5);

        assertThat(result).isSameAs(view);
        verify(fileGraphService).graphClusterOverview("myproj", 5);
    }
}
