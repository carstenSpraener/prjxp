package de.spraener.prjxp.chuno.docs.pdf;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class PdfChapterChunkerTest {
    @Test
    void chunkFachhKonzept() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test-info.pdf");
        if (resource != null) {
            String fqFileName = new File(resource.toURI()).getAbsolutePath();
            PdfChapterChunker uut = new PdfChapterChunker();
            uut.chunk(new File(fqFileName)).forEach(
                    c -> {
                        System.out.printf(
                                "\n******************\nChunk[%d] Id=%s, File '%s', Page '%d', Chapter '%s': %s\n",
                                c.getPart(), c.getId(), c.getFile(),
                                PdfChapterChunker.ChunkAccess.getPage(c),
                                PdfChapterChunker.ChunkAccess.getChapter(c),
                                c.getContent().substring(0, Math.min(10, c.getContent().length()))
                        );
                        System.out.println("\n\n"+c.getContent()+"\n\n");
                    }

            );
        }
    }
}