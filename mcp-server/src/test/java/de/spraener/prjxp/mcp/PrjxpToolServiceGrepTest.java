package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The grep surface follows "search returns references, read returns content": the default answer is
 * the grepped file list (grep -l), the matching line is opt-in.
 */
@ExtendWith(MockitoExtension.class)
class PrjxpToolServiceGrepTest {

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

    PrjxpToolService service;

    private static SearchHit fileHit(String file) {
        return new SearchHit("c-" + file, 0.0, file, null, null, null, "grep", Map.of());
    }

    @BeforeEach
    void setUp() {
        service = new PrjxpToolService(enrichment, grepSearchService, readerService, symbolReaderService,
                projectRegistry, fileListService, fileGraphService, semanticGraphService,
                chunkDaoProvider, graphSearchService, configCatalogService);
    }

    @Test
    void grepDefaultsToTheFileListWithoutLines() {
        when(grepSearchService.searchFiles(eq("run"), eq("p"), isNull(), anyInt(), anyBoolean(), eq(GrepMode.SUBSTRING)))
                .thenReturn(new GrepResult(List.of(fileHit("a/A.java"), fileHit("b/B.java")), 2, null));

        String out = service.grep("run", "p", null, null, 20, true, null);

        assertThat(out).contains("a/A.java");
        assertThat(out).contains("b/B.java");
        assertThat(out).doesNotContain(":12");
        verify(grepSearchService).searchFiles(eq("run"), eq("p"), isNull(), eq(20), eq(true), eq(GrepMode.SUBSTRING));
    }

    @Test
    void grepFileListWithoutMatchesSaysSo() {
        when(grepSearchService.searchFiles(anyString(), anyString(), isNull(), anyInt(), anyBoolean(),
                eq(GrepMode.SUBSTRING))).thenReturn(new GrepResult(List.of(), 0, null));

        String out = service.grep("zzz", "p", null, null, 20, true, null);

        assertThat(out).contains("No matches for \"zzz\"");
    }

    @Test
    void grepWithLinesLevelReturnsAnchoredHits() {
        SearchHit lineHit = new SearchHit("c1", 0.0, "a/A.java", 12, 12, "run();", "grep", Map.of());
        when(grepSearchService.searchMatches(anyString(), anyString(), isNull(), anyInt(), anyBoolean(), eq(GrepMode.SUBSTRING)))
                .thenReturn(new GrepResult(List.of(lineHit), 1, null));

        String out = service.grep("run", "p", null, null, 20, true, "lines");

        assertThat(out).contains("a/A.java:12: run();");
        verify(grepSearchService).searchMatches(eq("run"), eq("p"), isNull(), eq(20), eq(true), eq(GrepMode.SUBSTRING));
    }

    @Test
    void grepKeepsTheTruncationNote() {
        when(grepSearchService.searchFiles(anyString(), anyString(), isNull(), anyInt(), anyBoolean(), eq(GrepMode.SUBSTRING)))
                .thenReturn(new GrepResult(List.of(fileHit("a/A.java")), 5,
                        "Note: showing 1 of 5 files with matches; raise 'limit'."));

        String out = service.grep("run", "p", null, null, 20, true, "names");

        assertThat(out).contains("1 of 5 files");
    }
}
