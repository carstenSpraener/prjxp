package de.spraener.chuno.util;

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

        ContentSplitter uut = new ContentSplitter(chunkSize, overlap).withContentPrefix("FOOLISH CONTENT");
        List<PxChunk> chunks = uut.splitContent(content, 1, 4, () -> {
            PxChunk chunk = PxChunk.create(c -> {
                c.setId("foolishContent");
            });
            return chunk;
        });
        String unsplit = uut.unsplit(chunks);
        assertEquals(content, unsplit);
    }
}