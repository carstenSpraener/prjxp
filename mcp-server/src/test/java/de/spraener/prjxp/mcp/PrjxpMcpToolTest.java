package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrjxpMcpToolTest {

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
    void readFileDelegatesAndDefaultsProject() {
        when(readerService.read("src/Foo.java", "default", null, null))
                .thenReturn(FileView.error("src/Foo.java", "no index"));

        FileView view = tool.readFile("src/Foo.java", null, null, null);

        assertThat(view.error()).isEqualTo("no index");
        verify(readerService).read("src/Foo.java", "default", null, null);

        // a blank 'file' must short-circuit to an error view without touching the service
        FileView err = tool.readFile("", "prj", 1, 5);

        assertThat(err.content()).isNull();
        assertThat(err.error()).contains("file");
        verifyNoMoreInteractions(readerService);
    }

    @Test
    void vectorSearchUnknownProjectReturnsErrorString() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        String result = tool.vectorSearch("How does chunking work?", "nope", null, null, null);

        assertThat(result).startsWith("ERROR:").contains("nope").contains("alpha");
        verifyNoInteractions(enrichment);
    }

    @Test
    void vectorSearchResolvesProjectViaRegistry() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "How does chunking work?", 0.85, 20, false))
                .thenReturn("Relevante Information in 'myproj':\nchunk content");

        String result = tool.vectorSearch("How does chunking work?", "myproj", null, null, null);

        assertThat(result).contains("chunk content");
        verify(enrichment).enrich("myproj", "How does chunking work?", 0.85, 20, false);
    }

    @Test
    void vectorSearchDefaultProjectResolvesViaRegistry() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 20, false)).thenReturn("ctx");

        String result = tool.vectorSearch("q", "default", null, null, null);

        assertThat(result).contains("ctx");
        verify(enrichment).enrich("myproj", "q", 0.85, 20, false);
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
    void listFilesDefaultsProjectAndLimit() {
        when(fileListService.list(null, "default", 50)).thenReturn(new FileListResult(List.of("a.java"), false));

        FileListResult result = tool.listFiles(null, null, null);

        assertThat(result.files()).containsExactly("a.java");
        verify(fileListService).list(null, "default", 50);
    }

    @Test
    void listFilesClampsLimitToMax() {
        when(fileListService.list("src", "default", 500)).thenReturn(new FileListResult(List.of(), false));

        tool.listFiles("src", null, 10000);

        verify(fileListService).list("src", "default", 500);
    }

    @Test
    void graphFileDelegatesAndDefaultsProject() {
        when(fileGraphService.graphFile("src/A.java", "default", 5, true))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        GraphView view = tool.graphFile("  src/A.java  ", null, 5, true);

        assertThat(view.error()).isEqualTo("Graph nicht verfügbar, bitte reindexieren");
        verify(fileGraphService).graphFile("src/A.java", "default", 5, true);
    }

    @Test
    void graphNodeDelegatesAndDefaultsProject() {
        when(fileGraphService.graphNode("demo.A", "default", null, null))
                .thenReturn(GraphView.unavailable("Node nicht gefunden: demo.A"));

        GraphView view = tool.graphNode("demo.A", "", null, null);

        assertThat(view.error()).contains("demo.A");
        verify(fileGraphService).graphNode("demo.A", "default", null, null);
    }

    @Test
    void graphFileRequiresFile() {
        GraphView view = tool.graphFile("", "demo", null, null);

        assertThat(view.error()).isEqualTo("Parameter 'file' ist erforderlich.");
        verifyNoInteractions(fileGraphService);
    }
}
