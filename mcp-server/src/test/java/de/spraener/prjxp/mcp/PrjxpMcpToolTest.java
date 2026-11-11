package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrjxpMcpToolTest {

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

    private PrjxpMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpMcpTool(enrichment, grepSearchService, readerService, symbolReaderService, projectRegistry, fileListService, fileGraphService);
    }

    @Test
    void readFilePassesProjectThrough() {
        when(readerService.read("src/Foo.java", "myproj", null, null))
                .thenReturn(FileView.error("src/Foo.java", "no index"));

        FileView view = tool.readFile("src/Foo.java", "myproj", null, null);

        assertThat(view.error()).isEqualTo("no index");
        verify(readerService).read("src/Foo.java", "myproj", null, null);

        // a blank 'file' must short-circuit to an error view without touching the service
        FileView err = tool.readFile("", "prj", 1, 5);

        assertThat(err.content()).isNull();
        assertThat(err.error()).contains("file");
        verifyNoMoreInteractions(readerService);
    }

    @Test
    void vectorSearchUnknownProjectReturnsErrorString() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        String result = tool.vectorSearch("How does chunking work?", "nope", null, null, null, null, null, null);

        assertThat(result).startsWith("ERROR:").contains("nope").contains("alpha");
        verifyNoInteractions(enrichment);
    }

    @Test
    void vectorSearchResolvesProjectViaRegistry() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "How does chunking work?", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER))
                .thenReturn("Relevante Information in 'myproj':\nchunk content");

        String result = tool.vectorSearch("How does chunking work?", "myproj", null, null, null, null, null, null);

        assertThat(result).contains("chunk content");
        verify(enrichment).enrich("myproj", "How does chunking work?", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchDefaultProjectResolvesViaRegistry() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        String result = tool.vectorSearch("q", "default", null, null, null, null, null, null);

        assertThat(result).contains("ctx");
        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchDefaultsIncludeTestsToFalse() {
        // Semantic discovery: tests are noise — null includeTests must default to false
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchPassesExplicitIncludeTestsThrough() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, true, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null, true);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, true, CODE_FILTER);
    }

    // ---- the contentLevel knob ----

    @Test
    void vectorSearchWithoutContentLevelDefaultsToPointers() {
        // the MCP vectorSearch defaults to the cheap POINTERS path
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void vectorSearchWithContentLevelFullPassesFull() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.FULL, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, "FULL", null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.FULL, false, CODE_FILTER);
    }

    @Test
    void skeletonsOnlyTrueWithoutContentLevelStillMeansSkeleton() {
        // legacy mapping: the deprecated skeletonsOnly=TRUE still means SKELETON when contentLevel is absent
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.SKELETON, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, true, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.SKELETON, false, CODE_FILTER);
    }

    @Test
    void vectorSearchContentLevelWinsOverSkeletonsOnly() {
        // an explicit contentLevel is authoritative over the deprecated alias
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.FULL, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, true, "FULL", null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.FULL, false, CODE_FILTER);
    }

    // ---- the sourceType knob (cheap path) ----

    @Test
    void vectorSearchDefaultsToCodeSourceType() {
        // null sourceType must normalise to the code filter (excludes prose docs)
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false,
                Map.of(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf"));
    }

    @Test
    void vectorSearchExplicitSourceTypeDocsWinsOverDefault() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        Map<String, String> docsFilter = Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, "docs", null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter);
    }

    @Test
    void vectorSearchDefaultsMaxResultsToTen() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn("ctx");

        tool.vectorSearch("q", "myproj", null, null, null, null, null, null);

        verify(enrichment).enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER);
    }

    @Test
    void emptyCodeSearchAppendsSourceTypeHint() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        String deadEnd = "Es konnte kein valider Kontext erstellt werden! Effektive Similarity-Schwelle: 0.50 (3 Fallbacks).";

        // positive: sourceType=null -> code -> the dead-end hint is appended
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, CODE_FILTER)).thenReturn(deadEnd);
        String codeResult = tool.vectorSearch("q", "myproj", null, null, null, null, null, null);
        assertThat(codeResult).contains("sourceType=all");

        // negative: sourceType=docs -> the hint is NOT appended
        Map<String, String> docsFilter = Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
        when(enrichment.enrich("myproj", "q", 0.85, 10, ContentLevel.POINTERS, false, docsFilter)).thenReturn(deadEnd);
        String docsResult = tool.vectorSearch("q", "myproj", null, null, null, null, "docs", null);
        assertThat(docsResult).doesNotContain("sourceType=all");
    }

    @Test
    void grepDefaultsIncludeTestsToTrue() {
        // Exact search is deliberate intent: null includeTests must default to true
        when(grepSearchService.search("q", "myproj", null, 10, true, ContentLevel.FULL)).thenReturn(List.of());

        tool.grep("q", "myproj", null, null, null, null);

        verify(grepSearchService).search("q", "myproj", null, 10, true, ContentLevel.FULL);
    }

    @Test
    void grepPassesExplicitIncludeTestsThrough() {
        when(grepSearchService.search("q", "myproj", null, 10, false, ContentLevel.FULL)).thenReturn(List.of());

        tool.grep("q", "myproj", null, null, null, false);

        verify(grepSearchService).search("q", "myproj", null, 10, false, ContentLevel.FULL);
    }

    @Test
    void grepPassesContentLevelThrough() {
        // null contentLevel parses to FULL (today's behaviour); an explicit value is passed through
        when(grepSearchService.search("q", "myproj", null, 10, true, ContentLevel.POINTERS)).thenReturn(List.of());

        tool.grep("q", "myproj", null, null, "POINTERS", true);

        verify(grepSearchService).search("q", "myproj", null, 10, true, ContentLevel.POINTERS);
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

        FileListResult result = tool.listFiles(null, "myproj", null);

        assertThat(result.files()).containsExactly("a.java");
        verify(fileListService).list(null, "myproj", 50);
    }

    @Test
    void listFilesClampsLimitToMax() {
        when(fileListService.list("src", "myproj", 500)).thenReturn(new FileListResult(List.of(), false));

        tool.listFiles("src", "myproj", 10000);

        verify(fileListService).list("src", "myproj", 500);
    }

    @Test
    void graphFilePassesProjectThrough() {
        when(fileGraphService.graphFile("src/A.java", "myproj", 5, true))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        GraphView view = tool.graphFile("  src/A.java  ", "myproj", 5, true);

        assertThat(view.error()).isEqualTo("Graph nicht verfügbar, bitte reindexieren");
        verify(fileGraphService).graphFile("src/A.java", "myproj", 5, true);
    }

    @Test
    void graphNodePassesProjectThrough() {
        when(fileGraphService.graphNode("demo.A", "myproj", null, null))
                .thenReturn(GraphView.unavailable("Node nicht gefunden: demo.A"));

        GraphView view = tool.graphNode("demo.A", "myproj", null, null);

        assertThat(view.error()).contains("demo.A");
        verify(fileGraphService).graphNode("demo.A", "myproj", null, null);
    }

    @Test
    void graphFileRequiresFile() {
        GraphView view = tool.graphFile("", "demo", null, null);

        assertThat(view.error()).isEqualTo("Parameter 'file' ist erforderlich.");
        verifyNoInteractions(fileGraphService);
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
