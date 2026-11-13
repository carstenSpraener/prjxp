package de.spraener.prjxp.mcp;

import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrjxpMcpToolSemanticTest {

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

    private PrjxpMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpMcpTool(enrichment, grepSearchService, readerService,
                symbolReaderService, projectRegistry, fileListService, fileGraphService,
                semanticGraphService, chunkDaoProvider);
    }

    @Test
    void semanticClusterTrimsAndDelegates() {
        SemanticClusterView expected = new SemanticClusterView(
                "semantic", "p", "s3", 1, java.util.List.of(), null);
        when(semanticGraphService.semanticCluster("s3", "p", 10)).thenReturn(expected);

        assertThat(tool.semanticGraphCluster(" s3 ", "p", 10)).isSameAs(expected);
        verify(semanticGraphService).semanticCluster("s3", "p", 10);
    }

    @Test
    void semanticClusterRejectsBlankId() {
        assertThat(tool.semanticGraphCluster(" ", "p", 10).error()).contains("clusterId");
        verifyNoInteractions(semanticGraphService);
    }

    @Test
    void semanticNodeTrimsAndDelegates() {
        SemanticNodeView expected = new SemanticNodeView(
                "semantic", "p", "node-1", "a.md", "text/markdown", "s1",
                java.util.List.of(), null);
        when(semanticGraphService.semanticNode("node-1", "p", 5)).thenReturn(expected);

        assertThat(tool.semanticGraphNode(" node-1 ", "p", 5)).isSameAs(expected);
        verify(semanticGraphService).semanticNode("node-1", "p", 5);
    }

    @Test
    void semanticNodeRejectsBlankId() {
        assertThat(tool.semanticGraphNode("", "p", 5).error()).contains("nodeId");
        verifyNoInteractions(semanticGraphService);
    }

    @Test
    void semanticOverviewDelegates() {
        SemanticOverviewView expected = new SemanticOverviewView(
                "semantic", "p", 0, java.util.List.of(), "model", null, null);
        when(semanticGraphService.semanticOverview("p", 3)).thenReturn(expected);

        assertThat(tool.semanticGraphOverview("p", 3)).isSameAs(expected);
        verify(semanticGraphService).semanticOverview("p", 3);
    }
}
