package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 07: {@code readChunks} reassembles chunk parts from the index so the model can follow
 * the {@code neighbours} pointers of a vectorSearch result.
 */
@ExtendWith(MockitoExtension.class)
class ReadChunksToolTest {

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
    private PxChunkDaoProvider chunkDaoProvider;
    @Mock
    private PxChunkDao chunkDao;

    private PrjxpMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new PrjxpMcpTool(enrichment, grepSearchService, readerService, symbolReaderService,
                projectRegistry, fileListService, fileGraphService, semanticGraphService, chunkDaoProvider);
    }

    @Test
    void readsSingleChunk() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById("c1")).thenReturn(parts(part("c1", "src/Foo.java", 0, "class Foo {}")));

        String result = tool.readChunks("p1", "c1");

        assertThat(result)
                .contains("## c1")
                .contains("(src/Foo.java)")
                .contains("class Foo {}");
    }

    @Test
    void readsMultipleChunks() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById(anyString())).thenAnswer(invocation ->
                parts(part(invocation.getArgument(0), "src/A.java", 0, "body of " + invocation.getArgument(0))));

        String result = tool.readChunks("p1", "c1, c2 , c3");

        assertThat(result).contains("## c1", "## c2", "## c3");
        verify(chunkDao).findById("c1");
        verify(chunkDao).findById("c2");
        verify(chunkDao).findById("c3");
    }

    @Test
    void combinesMultiPartChunks() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById("c1")).thenReturn(parts(
                part("c1", "src/Foo.java", 0, "first-"),
                part("c1", "src/Foo.java", 1, "second")));

        String result = tool.readChunks("p1", "c1");

        assertThat(result).contains("first-second");
        assertThat(result).doesNotContain("first-\n\n## c1");
    }

    @Test
    void notFoundChunk() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById("missing")).thenReturn(new ArrayList<>());

        String result = tool.readChunks("p1", "missing");

        assertThat(result).contains("## missing").contains("(not found)");
    }

    @Test
    void capsAtTenIds() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById(anyString())).thenAnswer(invocation ->
                parts(part(invocation.getArgument(0), null, 0, "content")));

        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append("id").append(i);
        }

        tool.readChunks("p1", ids.toString());

        verify(chunkDao, times(10)).findById(anyString());
        verify(chunkDao).findById("id9");
        verify(chunkDao, never()).findById("id10");
    }

    @Test
    void contentTruncation() {
        String longContent = "x".repeat(5000);
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById("c1")).thenReturn(parts(part("c1", "src/Big.java", 0, longContent)));

        String result = tool.readChunks("p1", "c1");

        assertThat(result).contains("... [truncated]");
        assertThat(result).doesNotContain(longContent);
        assertThat(result.length()).isLessThan(2200);
    }

    @Test
    void totalCapReached() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById(anyString())).thenAnswer(invocation ->
                parts(part(invocation.getArgument(0), null, 0, "y".repeat(2000))));

        String result = tool.readChunks("p1", "a,b,c,d,e,f,g,h,i,j");

        assertThat(result).contains("... [total cap reached, remaining chunks omitted]");
    }

    @Test
    void missingProject() {
        when(chunkDaoProvider.get("nope")).thenReturn(Optional.empty());

        String result = tool.readChunks("nope", "c1");

        assertThat(result).startsWith("ERROR:").contains("Project").contains("not found");
        verifyNoInteractions(chunkDao);
    }

    @Test
    void blankArgumentsAreRejected() {
        assertThat(tool.readChunks(" ", "c1")).startsWith("ERROR:").contains("required");
        assertThat(tool.readChunks("p1", "  ")).startsWith("ERROR:").contains("required");
        verifyNoInteractions(chunkDaoProvider);
    }

    @Test
    void idsWithoutAnyRealValueAreRejected() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));

        assertThat(tool.readChunks("p1", " , ,")).contains("No valid chunk_ids");
        verifyNoInteractions(chunkDao);
    }

    @Test
    void chunkWithoutFileOmitsParentheses() {
        when(chunkDaoProvider.get("p1")).thenReturn(Optional.of(chunkDao));
        when(chunkDao.findById("c1")).thenReturn(parts(part("c1", null, 0, "plain")));

        String result = tool.readChunks("p1", "c1");

        assertThat(result).contains("## c1\nplain").doesNotContain("()");
    }

    // ==================== fixtures ====================

    private static List<PxChunk> parts(PxChunk... chunks) {
        return new ArrayList<>(List.of(chunks));
    }

    private static PxChunk part(String id, String file, int part, String content) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_FILE, file);
        metadata.put(PxChunk.PXCHUNK_PART, part);
        metadata.put(PxChunk.PXCHUNK_TOTAL, 1);
        return PxChunk.fromContentAndMap(content, metadata);
    }
}
