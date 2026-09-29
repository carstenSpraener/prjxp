package de.spraener.prjxp.common.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericFileViewProviderTest {

    private final GenericFileViewProvider provider = new GenericFileViewProvider();

    @Test
    void declaresGenericMimeTypeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("*/*");
        assertThat(provider.language()).isEqualTo("generic");
    }

    @Test
    void reconstructsSequentialUnitsInLineOrder() {
        PxChunk lines1To10 = unit("a", "1", "10", "part-A");
        PxChunk lines11To20 = unit("b", "11", "20", "part-B");
        PxChunk lines21To30 = unit("c", "21", "30", "part-C");

        String rendered = provider.render(List.of(lines21To30, lines1To10, lines11To20));

        assertThat(rendered).isEqualTo("part-A\npart-B\npart-C");
    }

    @Test
    void skipsUnitsNestedInWiderUnit() {
        PxChunk wide = unit("wide", "1", "100", "wide-content");
        PxChunk nested = unit("nested", "5", "10", "nested-content");

        String rendered = provider.render(List.of(nested, wide));

        assertThat(rendered).isEqualTo("wide-content");
    }

    @Test
    void skipsBlankUnits() {
        PxChunk normal = unit("normal", "1", "5", "real-content");
        PxChunk empty = unit("empty", "6", "10", "");
        PxChunk whitespace = unit("whitespace", "11", "15", "   ");
        PxChunk nullContent = unit("null-content", "16", "20", null);

        String rendered = provider.render(List.of(empty, normal, whitespace, nullContent));

        assertThat(rendered).isEqualTo("real-content");
    }

    @Test
    void handlesUnparseableLineNumbers() {
        // fromLine null -> 0, toLine "x" -> 0: degenerate range [0, 0], sorts first, emitted.
        PxChunk unparseable = unit("a", null, "x", "first");
        // [5, 15] is not contained in [0, 0] (15 > 0), so it is emitted second.
        PxChunk regular = unit("b", "5", "15", "second");

        String rendered = provider.render(List.of(regular, unparseable));

        assertThat(rendered).isEqualTo("first\nsecond");
    }

    private PxChunk unit(String id, String fromLine, String toLine, String content) {
        return PxChunk.create(
                chunk -> chunk.setId(id),
                chunk -> chunk.setPart(0),
                chunk -> chunk.setTotal(1),
                chunk -> chunk.setFromLine(fromLine),
                chunk -> chunk.setToLine(toLine),
                chunk -> chunk.setContent(content));
    }
}
