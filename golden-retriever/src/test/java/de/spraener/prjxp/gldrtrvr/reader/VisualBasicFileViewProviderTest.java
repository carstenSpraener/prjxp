package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisualBasicFileViewProviderTest {

    private static final String FILE = "src/example/Foo.vb";
    private static final String SECTION = "visualbasic_code_section";

    private final VisualBasicFileViewProvider provider = new VisualBasicFileViewProvider();

    /** Frame content fixture from the phase doc. */
    private static String fooFrame() {
        return "Imports System\n"
                + "\n"
                + "Public Class Foo\n"
                + "    Private count As Integer\n"
                + "    Public Sub Inc(ByVal a As Integer)\n"
                + "    Public Function GetCount() As Integer\n"
                + "End Class\n";
    }

    private static PxChunk unit(String id, String parent, String section, int fromLine, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setFile(FILE);
            c.setMimeType(VisualBasicFileViewProvider.MIME);
            c.setFromLine(String.valueOf(fromLine));
            c.setContent(content);
            c.getMetadata().put(SECTION, section);
        });
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void splicesSubBodyAtDeclarationLine() {
        PxChunk frame = unit("Foo", null, "classFrame", 1, fooFrame());
        PxChunk inc = unit("Foo.Inc", "Foo", "method", 5,
                "    Public Sub Inc(ByVal a As Integer)\n"
                        + "        count = count + a\n"
                        + "    End Sub\n");

        String view = provider.render(List.of(frame, inc));

        assertThat(countOccurrences(view, "count = count + a")).isEqualTo(1);
        assertThat(countOccurrences(view, "End Sub")).isEqualTo(1);
        assertThat(countOccurrences(view, "End Class")).isEqualTo(1);
    }

    @Test
    void overloadIdsBothSpliced() {
        PxChunk frame = unit("Foo", null, "classFrame", 1,
                "Public Class Foo\n"
                        + "    Public Sub Inc(ByVal a As Integer)\n"
                        + "    Public Sub Inc(ByVal a As Integer)\n"
                        + "End Class\n");
        PxChunk overload1 = unit("Foo.Inc.overload1", "Foo", "method", 2,
                "    Public Sub Inc(ByVal a As Integer)\n"
                        + "        count += a\n"
                        + "    End Sub\n");
        PxChunk overload2 = unit("Foo.Inc.overload2", "Foo", "method", 3,
                "    Public Sub Inc(ByVal a As Integer)\n"
                        + "        count *= a\n"
                        + "    End Sub\n");

        String view = provider.render(List.of(frame, overload1, overload2));

        assertThat(countOccurrences(view, "count += a")).isEqualTo(1);
        assertThat(countOccurrences(view, "count *= a")).isEqualTo(1);
        assertThat(countOccurrences(view, "End Sub")).isEqualTo(2);
        assertThat(view.indexOf("count += a")).isLessThan(view.indexOf("count *= a"));
    }

    @Test
    void docCommentsNotDuplicated() {
        PxChunk frame = unit("Foo", null, "classFrame", 1,
                "Public Class Foo\n"
                        + "    ' Increments count.\n"
                        + "    Public Sub Inc(ByVal a As Integer)\n"
                        + "End Class\n");
        PxChunk inc = unit("Foo.Inc", "Foo", "method", 3,
                "    Public Sub Inc(ByVal a As Integer)\n"
                        + "        count = count + a\n"
                        + "    End Sub\n");
        PxChunk doc = unit("Foo.Inc.doc", "Foo.Inc", "methodDoc", 2,
                "    ' Increments count.\n");

        String view = provider.render(List.of(frame, inc, doc));

        assertThat(countOccurrences(view, "' Increments count.")).isEqualTo(1);
        assertThat(countOccurrences(view, "count = count + a")).isEqualTo(1);
    }

    @Test
    void fallbackInsertsBeforeEndLine() {
        PxChunk frame = unit("Foo", null, "classFrame", 1,
                "Public Class Foo\n"
                        + "    Private count As Integer\n"
                        + "    Public Function GetCount() As Integer\n"
                        + "End Class\n");
        PxChunk inc = unit("Foo.Inc", "Foo", "method", 5,
                "    Public Sub Inc(ByVal a As Integer)\n"
                        + "        count = count + a\n"
                        + "    End Sub\n");

        String view = provider.render(List.of(frame, inc));

        assertThat(view).contains("        count = count + a");
        int endSubPos = view.indexOf("    End Sub");
        int endClassPos = view.lastIndexOf("End Class");
        assertThat(endSubPos).isNotNegative();
        assertThat(endSubPos).isLessThan(endClassPos);
    }

    @Test
    void noFrameFallsBackToJoin() {
        PxChunk a = unit("Foo.A", "Foo", "method", 2,
                "    Public Sub A()\n        doA()\n    End Sub\n");
        PxChunk b = unit("Foo.B", "Foo", "method", 8,
                "    Public Sub B()\n        doB()\n    End Sub\n");

        String view = provider.render(List.of(b, a));

        assertThat(view).doesNotContain("##");
        assertThat(view.indexOf("doA()")).isLessThan(view.indexOf("doB()"));
    }

    @Test
    void orphanMethodRenderedAsPlainBlock() {
        PxChunk frame = unit("Foo", null, "classFrame", 1, fooFrame());
        PxChunk orphan = unit("Bar.DoIt", "Bar", "method", 10,
                "    Public Sub DoIt()\n        doIt()\n    End Sub\n");

        String view = provider.render(List.of(frame, orphan));

        assertThat(view).contains("## Bar.DoIt\n\n```vb\n"
                + "    Public Sub DoIt()\n"
                + "        doIt()\n"
                + "    End Sub\n"
                + "```\n");
    }

    @Test
    void mimeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/x-visual-basic-code");
        assertThat(provider.language()).isEqualTo("visualbasic");
    }
}
