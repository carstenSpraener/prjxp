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
    void splicesConstructorBodyAtSignatureLine() {
        // Frame uses the REAL chunker signature format: getDeclarationAsString(false,false,false)
        // yields "Foo(int)" / "Foo(String)" — no modifiers, no parameter names (same as methods).
        String frameContent = "package de.example;\n"
                + "\n"
                + "import java.util.List;\n"
                + "\n"
                + "public class Foo {\n"
                + "    private int x;\n"
                + "\n"
                + "    Foo(int)\n"
                + "    @Deprecated\n"
                + "    Foo(String)\n"
                + "    void bar(int)\n"
                + "}\n";

        PxChunk frame = unit("de.example.Foo", null, "classFrame", 1, frameContent);

        // Constructor with body — id suffix matches the frame line; body is the real source
        PxChunk ctor1 = unit("de.example.Foo.Foo(int)", "de.example.Foo", "constructor", 8,
                "    public Foo(int a) {\n        x = a;\n    }\n");

        // Constructor with annotation (the annotation comes from the source range, once)
        PxChunk ctor2 = unit("de.example.Foo.Foo(String)", "de.example.Foo", "constructor", 9,
                "    @Deprecated\n    public Foo(String s) {\n        this.s = s;\n    }\n");

        // Javadoc for first constructor
        PxChunk ctor1Doc = unit("de.example.Foo.Foo(int).javadoc", "de.example.Foo.Foo(int)", "methodDoc", 8,
                "/** Primary constructor. */\n");

        // Method (still works)
        PxChunk bar = unit("de.example.Foo.void bar(int)", "de.example.Foo", "method", 10,
                "    public void bar(int a) {\n        x = a;\n    }\n");

        String view = provider.render(List.of(frame, ctor1, ctor2, ctor1Doc, bar));

        // Constructor body should be spliced in
        assertThat(view).contains("    public Foo(int a) {\n        x = a;\n    }");
        assertThat(view).contains("/** Primary constructor. */");

        // @Deprecated should appear only once (not duplicated)
        assertThat(countOccurrences(view, "@Deprecated")).isEqualTo(1);

        // Method body should still be spliced
        assertThat(view).contains("    public void bar(int a) {\n        x = a;\n    }");

        // Bare signature lines should be replaced (not present as bare)
        assertThat(view).doesNotContain("    Foo(int)\n");
        assertThat(view).doesNotContain("    Foo(String)\n");
    }

    @Test
    void mimeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/x-java-code");
        assertThat(provider.language()).isEqualTo("java");
    }

    @Test
    void splicesEnumConstantsAfterDeclarationLine() {
        // Frame: enum declaration line + field (synthetic frame content)
        String frameContent = "package de.example;\n"
                + "\n"
                + "public enum Color {\n"
                + "    private int value;\n"
                + "}\n";

        PxChunk frame = unit("de.example.Color", null, "classFrame", 1, frameContent);

        // Enum constants unit — the real source lines for all constants (matches chunker output)
        PxChunk constants = unit("de.example.Color.constants", "de.example.Color", "enumConstants", 3,
                "    RED,\n" +
                "    GREEN(42),\n" +
                "    BLUE {\n" +
                "        public String toString() { return \"Blue\"; }\n" +
                "    };\n");

        String view = provider.render(List.of(frame, constants));

        // Constants should appear after the declaration line and before the field
        assertThat(view).contains("public enum Color {\n    RED,\n    GREEN(42),");
        assertThat(view).contains("};");

        // Verify ordering: declaration < constants < field
        int declPos = view.indexOf("public enum Color {");
        int redPos = view.indexOf("RED,");
        int fieldPos = view.indexOf("private int value;");
        assertThat(declPos).isLessThan(redPos);
        assertThat(redPos).isLessThan(fieldPos);
    }

    @Test
    void enumConstantsFallbackBeforeClosingBrace() {
        // Frame without a proper "enum ... {" declaration line (malformed)
        String frameContent = "package de.example;\n" + "\n" + "Color {\n    private int value;\n}\n";

        PxChunk frame = unit("de.example.Color", null, "classFrame", 1, frameContent);
        PxChunk constants = unit("de.example.Color.constants", "de.example.Color", "enumConstants", 3,
                "    RED,\n    BLUE;\n");

        String view = provider.render(List.of(frame, constants));

        // Should fall back to inserting before the last "}" (constants present, before the close)
        int bluePos = view.indexOf("BLUE;");
        int closePos = view.lastIndexOf("}");
        assertThat(bluePos).isNotNegative();
        assertThat(bluePos).isLessThan(closePos);
    }

    @Test
    void rendersNestedTypeFramesInOrder() {
        // Realistic chunker format: signature lines without modifiers (getDeclarationAsString(false,false,false))
        String outerFrame = "package de.example;\n"
                + "\n"
                + "public class Outer {\n"
                + "    private String outerField;\n"
                + "\n"
                + "    void outerMethod()\n"
                + "}\n";

        String innerFrame = "package de.example;\n"
                + "\n"
                + "class Inner {\n"
                + "    private int innerValue;\n"
                + "\n"
                + "    void doInnerWork()\n"
                + "}\n";

        String enumFrame = "package de.example;\n"
                + "\n"
                + "enum NestedEnum {\n"
                + "}\n";

        PxChunk outer = unit("de.example.Outer", null, "classFrame", 1, outerFrame);
        PxChunk inner = unit("de.example.Outer.Inner", null, "classFrame", 6, innerFrame);
        PxChunk nestedEnum = unit("de.example.Outer.NestedEnum", null, "classFrame", 12, enumFrame);

        // Inner class method — id suffix matches the inner frame's signature line
        PxChunk innerMethod = unit("de.example.Outer.Inner.void doInnerWork()", "de.example.Outer.Inner", "method", 9,
                "    public void doInnerWork() {\n        System.out.println(innerValue);\n    }\n");

        // Enum constants
        PxChunk enumConstants = unit("de.example.Outer.NestedEnum.constants", "de.example.Outer.NestedEnum", "enumConstants", 13,
                "    ALPHA,\n    BETA;\n");

        // Pass in shuffled order — provider should sort by fromLine
        String view = provider.render(List.of(nestedEnum, innerMethod, outer, enumConstants, inner));

        // All three frames present as separate ## blocks, in fromLine order: outer (1) < inner (6) < enum (12)
        int outerStart = view.indexOf("## de.example.Outer\n");
        int innerStart = view.indexOf("## de.example.Outer.Inner\n");
        int enumStart = view.indexOf("## de.example.Outer.NestedEnum\n");
        assertThat(outerStart).isNotNegative();
        assertThat(innerStart).isGreaterThan(outerStart);
        assertThat(enumStart).isGreaterThan(innerStart);

        String outerBlock = view.substring(outerStart, innerStart);
        String innerBlock = view.substring(innerStart, enumStart);
        String enumBlock = view.substring(enumStart);

        // Inner method body spliced into the INNER frame (bare signature line replaced)
        assertThat(innerBlock).contains("    public void doInnerWork() {\n        System.out.println(innerValue);\n    }");
        assertThat(innerBlock).doesNotContain("    void doInnerWork()\n");

        // The inner method must NOT be spliced into the outer (ancestor) frame
        assertThat(outerBlock).doesNotContain("doInnerWork");

        // Enum constants spliced into the enum frame
        assertThat(enumBlock).contains("ALPHA,");
        assertThat(enumBlock).contains("BETA;");
    }
}
