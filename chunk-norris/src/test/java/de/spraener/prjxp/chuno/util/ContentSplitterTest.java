package de.spraener.prjxp.chuno.util;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ContentSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContentSplitterTest {

    @Test
    public void testSplitUnSplitt() {
        String content =
                "1234567890 1234567890 1234567890 1234567890\n" +
                        "1234567890 1234567890 1234567890 1234567890\n" +
                        "1234567890 1234567890 1234567890 1234567890\n" +
                        "1234567890 1234567890 1234567890 1234567890\n";
        final int chunkSize = 37;
        final int overlap = 13;

        ContentSplitter uut = new ContentSplitter(chunkSize, overlap);
        List<PxChunk> chunks = uut.splitContent(content, 1, 4, () -> {
            PxChunk chunk = PxChunk.create(c -> {
                c.setId("foolishContent");
            });
            return chunk;
        });
        String unsplit = uut.unsplit(chunks);
        assertEquals(content, unsplit);
    }

    @Test
    public void testSplitAddsLineCountNumerically() {
        String content = "line01\nline02\nline03\nline04\nline05\nline06\nline07\nline08\n";
        ContentSplitter uut = new ContentSplitter(20, 0);

        List<PxChunk> chunks = uut.splitContent(content, 100, 108, () -> PxChunk.create(c -> c.setId("lines")));

        assertEquals("100", chunks.get(0).getFromLine());
        assertEquals("102", chunks.get(0).getToLine());
        assertEquals("102", chunks.get(1).getFromLine());
        assertEquals("105", chunks.get(1).getToLine());
    }
}