package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaFileViewProviderTest {

    private static final String FILE = "de/example/Foo.java";
    private static final String SECTION = "java_code_section";

    private final JavaFileViewProvider provider = new JavaFileViewProvider();

    /** Frame content fixture from the phase doc (2 methods, one with annotation). */
    private static String fooFrame() {
        return "package de.example;\n"
                + "\n"
                + "import java.util.List;\n"
                + "\n"
                + "public class Foo {\n"
                + "    private int x;\n"
                + "\n"
                + "    public void bar(int a)\n"
                + "    @Override\n"
                + "    public String qux()\n"
                + "}\n";
    }

    private static PxChunk unit(String id, String parent, String section, int fromLine, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setFile(FILE);
            c.setMimeType(JavaFileViewProvider.MIME);
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
    void splicesMethodBodyAtSignatureLine() {
        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1, fooFrame());
        PxChunk bar = unit("de.example.Foo.public void bar(int a)", "de.example.Foo", "method", 8,
                "    public void bar(int a) {\n        x = a;\n    }\n");

        String view = provider.render(List.of(frame, bar));

        assertThat(view).contains("    public void bar(int a) {\n        x = a;\n    }");
        assertThat(view).doesNotContain("    public void bar(int a)\n");
        assertThat(countOccurrences(view, "x = a;")).isEqualTo(1);
    }

    @Test
    void insertsJavadocAboveBodyAndDropsDuplicateAnnotation() {
        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1, fooFrame());
        PxChunk qux = unit("de.example.Foo.public String qux()", "de.example.Foo", "method", 9,
                "    @Override\n    public String qux() {\n        return \"\" + x;\n    }\n");
        PxChunk doc = unit("de.example.Foo.public String qux().javadoc", "de.example.Foo.public String qux()", "methodDoc", 9,
                "/** Does qux. */\n");

        String view = provider.render(List.of(frame, qux, doc));

        int docPos = view.indexOf("/** Does qux. */");
        int overridePos = view.indexOf("@Override");
        int declPos = view.indexOf("    public String qux() {");
        assertThat(docPos).isNotNegative();
        assertThat(overridePos).isNotNegative();
        assertThat(declPos).isNotNegative();
        assertThat(docPos).isLessThan(overridePos);
        assertThat(overridePos).isLessThan(declPos);
        assertThat(countOccurrences(view, "@Override")).isEqualTo(1);
    }

    @Test
    void rendersMultipleFramesInOrder() {
        PxChunk foo = unit("de.example.Foo", null, "classFrame", 1, "package de.example;\n\npublic class Foo {\n}\n");
        PxChunk bar = unit("de.example.Bar", null, "classFrame", 30, "package de.example;\n\npublic class Bar {\n}\n");

        String view = provider.render(List.of(bar, foo));

        assertThat(view).contains("## de.example.Foo").contains("## de.example.Bar");
        assertThat(view.indexOf("## de.example.Foo")).isLessThan(view.indexOf("## de.example.Bar"));
    }

    @Test
    void orphanMethodRenderedAsPlainBlock() {
        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1, fooFrame());
        PxChunk orphan = unit("de.example.Other.helper(int)", "de.example.Other", "method", 40,
                "    void helper(int a) {\n    }\n");
        PxChunk doc = unit("de.example.Other.helper(int).javadoc", "de.example.Other.helper(int)", "methodDoc", 39,
                "/** Helper doc. */\n");

        String view = provider.render(List.of(frame, orphan, doc));

        assertThat(view).contains("## de.example.Other.helper(int)\n\n```java\n/** Helper doc. */\n    void helper(int a) {\n    }\n```\n");
    }

    @Test
    void fallbackWhenNoFrame() {
        PxChunk bar = unit("de.example.Foo.public void bar(int a)", "de.example.Foo", "method", 8,
                "    public void bar(int a) {\n        x = a;\n    }\n");
        PxChunk qux = unit("de.example.Foo.public String qux()", "de.example.Foo", "method", 10,
                "    public String qux() {\n        return \"x\";\n    }\n");

        String view = provider.render(List.of(bar, qux));

        assertThat(view).doesNotContain("##");
        assertThat(view).contains("x = a;").contains("public String qux() {");
        assertThat(view.indexOf("x = a;")).isLessThan(view.indexOf("public String qux() {"));
    }

    @Test
    void spliceFallsBackToBeforeClosingBrace() {
        PxChunk bar = unit("de.example.Foo.public void bar(int a)", "de.example.Foo", "method", 8,
                "    public void bar(int a) {\n        x = a;\n    }\n");

        // Scenario A: skeleton does not contain the sig line, but has a closing brace
        // -> body is inserted before the last "}" line, skeleton lines untouched.
        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1,
                "package de.example;\n\npublic class Foo {\n    private int x;\n\n    void unrelated()\n}\n");

        String view = provider.render(List.of(frame, bar));

        assertThat(view).contains("    void unrelated()");
        assertThat(view).contains("x = a;\n    }\n\n}");

        // Scenario B: skeleton has no "}" line at all -> body is appended at the end.
        PxChunk truncatedFrame = unit("de.example.Foo", null, "classFrame", 1,
                "package de.example;\n\npublic class Foo {\n    private int x;\n    void unrelated()\n");

        String viewWithoutClosingBrace = provider.render(List.of(truncatedFrame, bar));

        assertThat(viewWithoutClosingBrace).contains("    void unrelated()\n\n    public void bar(int a) {\n        x = a;\n    }");
    }

    @Test
    void skipsNothingForImportsUnits() {
        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1, fooFrame());
        PxChunk bar = unit("de.example.Foo.public void bar(int a)", "de.example.Foo", "method", 8,
                "    public void bar(int a) {\n        x = a;\n    }\n");
        PxChunk imports = unit("de.example.Foo.imports", "de.example.Foo", "imports", 3,
                "import java.util.Map;\n");

        String view = provider.render(List.of(frame, bar, imports));

        assertThat(view).doesNotContain("import java.util.Map;");
    }

    @Test
    void mimeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/x-java-code");
        assertThat(provider.language()).isEqualTo("java");
    }
}
