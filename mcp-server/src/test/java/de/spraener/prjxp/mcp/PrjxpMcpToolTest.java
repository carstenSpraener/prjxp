package de.spraener.prjxp.mcp;

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

    private PrjxpMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpMcpTool(enrichment, grepSearchService, readerService, symbolReaderService, projectRegistry);
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
    void listProjectsDelegatesToRegistry() {
        when(projectRegistry.availableProjects()).thenReturn(List.of("alpha", "beta"));

        assertThat(tool.listProjects()).containsExactly("alpha", "beta");
    }
}
