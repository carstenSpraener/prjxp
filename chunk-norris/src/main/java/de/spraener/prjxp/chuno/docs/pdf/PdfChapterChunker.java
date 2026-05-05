package de.spraener.prjxp.chuno.docs.pdf;

import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.stream.Stream;

@Component
@ChunkNorrisComponent
public class PdfChapterChunker {

    @Chunker(fileTypes = {PxFileType.PDF})
    public Stream<PxChunk> chunk(File pdf) {
        Document document = FileSystemDocumentLoader.loadDocument(
                pdf.toPath(),
                new ApachePdfBoxDocumentParser()
        );
        DocumentSplitter splitter = DocumentSplitters.recursive(
                600, // Maximale Zeichen (ca. ein guter Absatz)
                50   // Überlappung, damit der Kontext erhalten bleibt
        );
        final PdfDocSplittingSession splitting = new PdfDocSplittingSession();
        splitting.setDocumentPath(pdf.getAbsolutePath());
        splitting.setLine(0);
        splitting.setPage(0);
        splitting.setChapter("NONE");
        return splitter.split(document)
                .stream()
                .map(s->this.convertSegment2Chunk(splitting, s));
    }

    private PxChunk convertSegment2Chunk(PdfDocSplittingSession splitting, TextSegment textSegment) {
        return PxChunk.create(
                c-> {
                    String pageNumber = (String) textSegment.metadata().toMap().getOrDefault("page_number", "0");
                    splitting.setPage(Integer.parseInt(pageNumber));
                    textSegment.metadata().toMap().forEach((k,v)->c.getMetadata().put(k, ""+v));
                    c.setFile(""+textSegment.metadata().toMap().getOrDefault("file_name", "unknown"));
                    c.setMimeType("application/pdf");
                    c.setId(splitting.getDocumentPath()+"."+splitting.getChapter());
                    textSegment.metadata().toMap().forEach((key, value) -> {
                        System.out.println("Gefundener Key: " + key + " mit Wert: " + value);
                    });
                    c.setContent(textSegment.text());
                    c.setPart(splitting.incrementChunkCount());
                }
        );
    }

    public class ChunkAccess {
        public static final String CHAPTER_KEY = "chapter";
        public static final String PAGE_KEY = "page";

        public static String getPage(PxChunk c) {
            return c.getMetadata().get(PAGE_KEY);
        }

        public static PxChunk setPage(PxChunk c, String value) {
            c.getMetadata().put(PAGE_KEY, value);
            return c;
        }

        public static String getChapter(PxChunk c) {
            return c.getMetadata().get(CHAPTER_KEY);
        }

        public static PxChunk setChapter(PxChunk c, String value) {
            c.getMetadata().put(CHAPTER_KEY, value);
            return c;
        }
    }
}
