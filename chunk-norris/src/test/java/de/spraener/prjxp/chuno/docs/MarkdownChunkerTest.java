package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.chuno.docs.config.ConversionRoutesConfig;
import de.spraener.prjxp.chuno.docs.html.Html2MDConversionAgent;
import de.spraener.prjxp.chuno.docs.txt.Text2MDConversionAgent;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.BeanNameFinder;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.common.util.SpringContextSupplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarkdownChunkerTest {
    DocConversionRouter emptyConverter =  mock(DocConversionRouter.class);
    String mdContent;
    @BeforeEach
    public void setup() {
        Mockito.when(emptyConverter.doConversion(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(
                i -> {
                    return this.mdContent;
                }
        );
    }

    @Test
    void processFile() throws Exception {
        String h1 = "# The Magic of Markdown";
        String toc = """
        1 Introduction                          3
        1.1 Why using MD                        3
        2 Examples                              5
        2.1 Example 1                           5
        2.2 Example 2                           7
        """;

        String p1_intro = "1 Introduction";
        String p1_text = "This paragraph is a small example of how to write\nMarkdown in a small paragraph.";
        String p2_text = "Every paragraph is seperated by a simple empty line.";
        String p3_text = "Or even more than one empty line.";

        String p1_1_header = "1.1 Why using MD";
        String p1_1_text = "MD is simple and easy to use.";

        String p2_header = "2 Example";

        String p2_1_header = "2.1 Example 1";
        String p2_1_text_a = """
        p2_1_text_a: Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut
        labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores
        et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
        ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et
        dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum.
        Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.""";

        String p2_1_text_b = "p2_1_text_b: " +
                "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor " +
                "invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo " +
                "duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit " +
                "amet. Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt " +
                "ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores " +
                "et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.";

        String p2_2_header = "2.2 Example 2";
        String p2_2_text_long = """
        p2_2_text_long: Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut
        labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores
        et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
        ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et
        dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum.
        Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum dolor sit
        amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna
        aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita
        kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum dolor sit amet,
        consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam
        erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd
        gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.""";

        String p2_2_text_final = """
        p2_2_text_final: Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut
        labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores
        et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
        ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et
        dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum.
        Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.""";

        this.mdContent = String.join("\n\n",
                h1,
                toc,
                p1_intro,
                p1_text,
                p2_text,
                p3_text,
                p1_1_header,
                p1_1_text,
                p2_header,
                p2_1_header,
                p2_1_text_a,
                p2_1_text_b,
                p2_2_header,
                p2_2_text_long,
                p2_2_text_final
        );

        MetaInfReader metaInfReader = mock(MetaInfReader.class);
        when(metaInfReader.readMetaInf(any())).thenReturn(
                Map.of("version", "0.3", "date", "20.04.1968", "promptFormat", "[[Version {version} vom {date}]]")
        );
        PxLogService logService = mock(PxLogService.class);
        MarkdownChunker chunker = new MarkdownChunker(logService, emptyConverter, metaInfReader);
        List<PxChunk> chunks = chunker.processFile(new File("dummy.md")).toList();

        Assertions.assertThat(chunks)
                .isNotEmpty()
                .anyMatch(c->c.getContent().contains(p1_text.trim()))
                .anyMatch(c->c.getContent().contains(p2_text.trim()))
                .anyMatch(c->c.getContent().contains(p3_text.trim()))
                // p1_1_text is less than 40 characters
                .noneMatch(c->c.getContent().contains(p1_1_text.trim()))
                .anyMatch(c->c.getContent().contains("p2_1_text_a: "))
                .anyMatch(c->c.getContent().contains("p2_1_text_b: "))
        ;

        PxChunk longPart0 = chunks.stream().filter(c -> c.getContent().contains("p2_2_text_long:")).findFirst().get();
        assertTrue(longPart0.getContent().startsWith(p2_2_text_long.substring(0, 50)));

        for( PxChunk c : chunks ) {
            assertTrue(c.getMetadata().containsKey("version"));
            assertTrue(c.getMetadata().containsKey("date"));
            assertTrue(c.getMetadata().containsKey("promptFormat"));
            assertEquals(c.getMetadata().get("version"), "0.3");
            assertEquals(c.getMetadata().get("date"), "20.04.1968");
            assertEquals(c.getMetadata().get("promptFormat"), "[[Version {version} vom {date}]]");
        }
    }

    /**
     * Regression test for '#'-style headers (like a typical README.md): isEndOfParagraph only
     * matches *numbered* headers, so with '#' headers the accumulated content was never flushed
     * per section — the entire document ended up in one chunk attributed to the LAST section.
     * Each section's content must be attributed to its own section.
     */
    @Test
    void hashHeaderSectionsGetTheirOwnContent() throws Exception {
        String intro = "Intro paragraph with enough content to pass the forty character minimum for chunking.";
        String sectionA = "Content of section A that is long enough to be included in a chunk, definitely more than forty chars.";
        String sectionB = "Content of section B that is long enough to be included in a chunk, definitely more than forty chars.";
        this.mdContent = String.join("\n",
                "# Top",
                intro,
                "",
                "## Section A",
                sectionA,
                "",
                "## Section B",
                sectionB
        );

        MetaInfReader metaInfReader = mock(MetaInfReader.class);
        when(metaInfReader.readMetaInf(any())).thenReturn(Map.of());
        PxLogService logService = mock(PxLogService.class);
        MarkdownChunker chunker = new MarkdownChunker(logService, emptyConverter, metaInfReader);
        List<PxChunk> chunks = chunker.processFile(new File("dummy.md")).toList();

        assertSectionAttribution(chunks, "Intro paragraph", "01", "Top");
        assertSectionAttribution(chunks, "Content of section A", "01.01", "Top > Section A");
        assertSectionAttribution(chunks, "Content of section B", "01.02", "Top > Section B");
    }

    private static void assertSectionAttribution(List<PxChunk> chunks, String contentMarker, String expectedSection, String expectedHierarchy) {
        List<PxChunk> matching = chunks.stream()
                .filter(c -> c.getContent() != null && c.getContent().contains(contentMarker))
                .toList();
        assertFalse(matching.isEmpty(), "Expected a chunk containing '" + contentMarker + "'");
        for (PxChunk c : matching) {
            assertEquals(expectedSection, c.getMetadata().get("section_number"),
                    "Content '" + contentMarker + "' must be attributed to section " + expectedSection);
            assertEquals(expectedHierarchy, c.getMetadata().get("hierarchy"),
                    "Content '" + contentMarker + "' must have hierarchy '" + expectedHierarchy + "'");
        }
    }

    /**
     * End-to-end regression test for the Docker hub scenario: an HTML file goes through
     * the REAL DocConversionRouter (root artifakt holds a File) into Html2MDConversionAgent
     * (expects a String). Previously this failed with a ClassCastException.
     */
    @Test
    void processHtmlFileWithRealRouter() throws Exception {
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeanNamesForType(any(Class.class))).thenReturn(new String[0]);
        SpringContextSupplier ctxSupplier = mock(SpringContextSupplier.class);
        when(ctxSupplier.getContext()).thenReturn(appCtx);
        BeanNameFinder beanNameFinder = new BeanNameFinder(ctxSupplier);
        ConversionRoutesConfig routesConfig = mock(ConversionRoutesConfig.class);
        DocConversionRouter realRouter = new DocConversionRouter(
                List.of(new Html2MDConversionAgent(), new Text2MDConversionAgent()),
                beanNameFinder,
                routesConfig
        );

        MetaInfReader metaInfReader = mock(MetaInfReader.class);
        when(metaInfReader.readMetaInf(any())).thenReturn(Map.of());
        PxLogService logService = mock(PxLogService.class);
        MarkdownChunker chunker = new MarkdownChunker(logService, realRouter, metaInfReader);

        File html = File.createTempFile("prjxp-test", ".html");
        html.deleteOnExit();
        Files.writeString(html.toPath(),
                "<html><body><h1>Doc Title</h1><p>Some paragraph content that is long enough to be chunked.</p></body></html>");

        List<PxChunk> chunks = chunker.processFile(html).toList();

        Assertions.assertThat(chunks)
                .isNotEmpty()
                .anyMatch(c -> c.getContent().contains("Some paragraph content"));
    }

}