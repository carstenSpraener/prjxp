package de.spraener.prjxp.gldrtrvr.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarkdownFileViewProviderTest {

    private static final String FILE = "doc.md";

    private MarkdownFileViewProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MarkdownFileViewProvider();
    }

    @Test
    void reassemblesSectionsInDocumentOrder() {
        List<PxChunk> units = List.of(
                paragraph("01.02", "01", "Para 1.2-1\n", Map.of("h1", "Einführung", "h2", "1.2 Unterpunkt")),
                fileUnit(),
                paragraph("01", "01", "Para 1-1\n", Map.of("h1", "Einführung")),
                section("02", "Fazit"),
                paragraph("01", "02", "Para 1-2\n", Map.of("h1", "Einführung")),
                section("01", "Einführung"),
                section("01.02", "1.2 Unterpunkt"),
                paragraph("02", "01", "Para 2-1\n", Map.of("h1", "Fazit")));

        String out = provider.render(units);

        assertInOrder(
                out.indexOf("## Einführung"),
                out.indexOf("Para 1-1"),
                out.indexOf("Para 1-2"),
                out.indexOf("## 1.2 Unterpunkt"),
                out.indexOf("Para 1.2-1"),
                out.indexOf("## Fazit"),
                out.indexOf("Para 2-1"));
    }

    @Test
    void zeroPaddedSectionNumbersSortCorrectly() {
        List<PxChunk> units = List.of(
                paragraph("01.10", "01", "Para 1.10\n", Map.of()),
                section("01.10", "Zehn"),
                paragraph("01.02", "01", "Para 1.02\n", Map.of()),
                section("01.02", "Zwei"));

        String out = provider.render(units);

        assertThat(out.indexOf("## Zwei")).isLessThan(out.indexOf("## Zehn"));
        assertThat(out.indexOf("Para 1.02")).isLessThan(out.indexOf("Para 1.10"));
    }

    @Test
    void skipsEmptyFileUnitAndEmitsHeaderOnlySections() {
        List<PxChunk> units = List.of(fileUnit(), section("02", "Fazit"));

        assertThat(provider.render(units)).isEqualTo("## Fazit\n");
    }

    @Test
    void paragraphWithoutSectionUnitKeepsHierarchyHeader() {
        List<PxChunk> units = List.of(
                paragraph("04", "01", "Orphan two\n", Map.of()),
                paragraph("03", "01", "Orphan text\n", Map.of("h1", "3 Kap")),
                paragraph("01", "01", "Intro para\n", Map.of("h1", "Einführung")),
                section("01", "Einführung"));

        String out = provider.render(units);

        assertInOrder(
                out.indexOf("## Einführung"),
                out.indexOf("Intro para"),
                out.indexOf("## 3 Kap"),
                out.indexOf("Orphan text"),
                out.indexOf("## " + FILE + ":04"),
                out.indexOf("Orphan two"));
    }

    @Test
    void paragraphIndexOrderWithinSection() {
        List<PxChunk> units = List.of(
                paragraph("01", "02", "Para NN02\n", Map.of()),
                paragraph("01", "10", "Para NN10\n", Map.of()),
                paragraph("01", "01", "Para NN01\n", Map.of()),
                section("01", "Kapitel"));

        String out = provider.render(units);

        assertThat(out.indexOf("Para NN01")).isLessThan(out.indexOf("Para NN02"));
        assertThat(out.indexOf("Para NN02")).isLessThan(out.indexOf("Para NN10"));
    }

    @Test
    void flatDocumentFallsBackToLineOrder() {
        List<PxChunk> units = List.of(
                fileUnit(),
                unit(FILE, FILE, "Line A\n", "10", Map.of("type", "md_paragraph")),
                unit(FILE, FILE, "Line B\n", "2", Map.of("type", "md_paragraph")),
                unit(FILE, FILE, "Line C\n", "20", Map.of("type", "md_paragraph")));

        String out = provider.render(units);

        assertThat(out.indexOf("Line B")).isLessThan(out.indexOf("Line A"));
        assertThat(out.indexOf("Line A")).isLessThan(out.indexOf("Line C"));
        assertThat(out).doesNotContain("## ");
    }

    @Test
    void mimeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/markdown");
        assertThat(provider.language()).isEqualTo("markdown");
    }

    // --- fixtures --------------------------------------------------------

    private PxChunk fileUnit() {
        return unit(FILE, null, "", null, Map.of("pxchunk_type", "FILE"));
    }

    private PxChunk section(String number, String header) {
        return unit(FILE + ":" + number, FILE, header, null,
                Map.of("pxchunk_type", "SECTION", "section_number", number));
    }

    private PxChunk paragraph(String sectionNumber, String nn, String content, Map<String, String> extra) {
        Map<String, String> metadata = new HashMap<>(Map.of("section_number", sectionNumber, "type", "md_paragraph"));
        metadata.putAll(extra);
        return unit(FILE + ":" + sectionNumber + ":" + nn, FILE + ":" + sectionNumber, content, null, metadata);
    }

    private PxChunk unit(String id, String parent, String content, String fromLine, Map<String, String> metadata) {
        return PxChunk.create(c -> {
            c.setMimeType(MarkdownFileViewProvider.MIME);
            c.setFile(FILE);
            c.setId(id);
            c.setParent(parent);
            c.setFromLine(fromLine);
            c.setContent(content);
            c.getMetadata().putAll(metadata);
        });
    }

    private static void assertInOrder(int... positions) {
        assertThat(Arrays.stream(positions).allMatch(p -> p >= 0))
                .as("all markers must be present: %s", Arrays.toString(positions))
                .isTrue();
        for (int i = 0; i < positions.length - 1; i++) {
            assertThat(positions[i])
                    .as("marker %d before marker %d", i, i + 1)
                    .isLessThan(positions[i + 1]);
        }
    }
}
