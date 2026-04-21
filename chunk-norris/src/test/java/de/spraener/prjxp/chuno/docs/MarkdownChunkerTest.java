package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ContentSplitter;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
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
        MarkdownChunker chunker = new MarkdownChunker(emptyConverter, metaInfReader);
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

}