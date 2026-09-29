package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypeScriptFileViewProviderTest {

    private static final String FILE = "src/example/foo.ts";
    private static final String SECTION = "typescript_code_section";

    private final TypeScriptFileViewProvider provider = new TypeScriptFileViewProvider();

    /** Frame content fixture from the phase doc. */
    private static String fooFrame() {
        return "import { x } from './x';\n"
                + "\n"
                + "export class Foo {\n"
                + "    public count: number;\n"
                + "    public inc(a: number): number;\n"
                + "}\n";
    }

    private static PxChunk unit(String id, String parent, String section, int fromLine, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setFile(FILE);
            c.setMimeType(TypeScriptFileViewProvider.MIME);
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
    void splicesMethodBodyReplacingSemicolonSignature() {
        PxChunk frame = unit("mod.Foo", null, "classFrame", 1, fooFrame());
        PxChunk inc = unit("mod.Foo.inc", "mod.Foo", "method", 5,
                "\t// Method inc in class Foo:\n"
                        + "    public inc(a: number): number {\n"
                        + "        return a + 1;\n"
                        + "    }\n");

        String view = provider.render(List.of(frame, inc));

        assertThat(countOccurrences(view, "return a + 1;")).isEqualTo(1);
        assertThat(view).doesNotContain("public inc(a: number): number;");
        assertThat(view).doesNotContain("// Method");
    }

    @Test
    void insertsJsDocAboveBodyAndStripsItsPrefix() {
        PxChunk frame = unit("mod.Foo", null, "classFrame", 1, fooFrame());
        PxChunk inc = unit("mod.Foo.inc", "mod.Foo", "method", 5,
                "    public inc(a: number): number {\n"
                        + "        return a + 1;\n"
                        + "    }\n");
        PxChunk doc = unit("mod.Foo.inc.jsdoc", "mod.Foo.inc", "methodDoc", 4,
                "\t// Method inc in class Foo:\n"
                        + "    /** Increments. */\n");

        String view = provider.render(List.of(frame, inc, doc));

        int docPos = view.indexOf("/** Increments. */");
        int bodyPos = view.indexOf("    public inc(a: number): number {");
        assertThat(docPos).isNotNegative();
        assertThat(bodyPos).isNotNegative();
        assertThat(docPos).isLessThan(bodyPos);
        assertThat(view).doesNotContain("// Method");
    }

    @Test
    void topLevelFunctionRenderedAsOrphanBlock() {
        PxChunk frame = unit("mod.Foo", null, "classFrame", 1, fooFrame());
        PxChunk helper = unit("mod.helper", "mod", "method", 8,
                "    function helper(a: number): number {\n"
                        + "        return a;\n"
                        + "    }\n");
        PxChunk doc = unit("mod.helper.jsdoc", "mod", "methodDoc", 7,
                "    /** Helper doc. */\n");
        PxChunk imports = unit("mod.imports", "mod", "imports", 1,
                "import { y } from './y';\n");

        String view = provider.render(List.of(frame, helper, doc, imports));

        assertThat(view).contains("## mod.helper\n\n```typescript\n"
                + "    /** Helper doc. */\n"
                + "    function helper(a: number): number {\n"
                + "        return a;\n"
                + "    }\n"
                + "```\n");
        assertThat(view).doesNotContain("import { y } from './y';");
    }

    @Test
    void multiLineDeclarationMatches() {
        PxChunk frame = unit("mod.Foo", null, "classFrame", 1,
                "export class Foo {\n"
                        + "    public multi(a: number,\n"
                        + "    b: number): void;\n"
                        + "}\n");
        PxChunk multi = unit("mod.Foo.multi", "mod.Foo", "method", 2,
                "\t// Method multi in class Foo:\n"
                        + "    public multi(a: number,\n"
                        + "    b: number): void {\n"
                        + "        x(a, b);\n"
                        + "    }\n");

        String view = provider.render(List.of(frame, multi));

        assertThat(view).contains("    public multi(a: number,\n"
                + "    b: number): void {\n"
                + "        x(a, b);\n"
                + "    }");
        assertThat(countOccurrences(view, "x(a, b);")).isEqualTo(1);
    }

    @Test
    void spliceFallsBackToBeforeClosingBrace() {
        PxChunk frame = unit("mod.Foo", null, "classFrame", 1,
                "export class Foo {\n"
                        + "    public other(): void;\n"
                        + "}\n");
        PxChunk inc = unit("mod.Foo.inc", "mod.Foo", "method", 3,
                "    public inc(a: number): number {\n"
                        + "        return a + 1;\n"
                        + "    }\n");

        String view = provider.render(List.of(frame, inc));

        assertThat(view).contains("    public other(): void;\n"
                + "    public inc(a: number): number {\n"
                + "        return a + 1;\n"
                + "    }\n");
        assertThat(view.indexOf("return a + 1;")).isLessThan(view.lastIndexOf("}"));
    }

    @Test
    void fallbackWhenNoFrame() {
        PxChunk alpha = unit("mod.Foo.alpha", "mod.Foo", "method", 3,
                "    function alpha(): void {\n        doA();\n    }\n");
        PxChunk beta = unit("mod.Foo.beta", "mod.Foo", "method", 9,
                "    function beta(): void {\n        doB();\n    }\n");

        String view = provider.render(List.of(beta, alpha));

        assertThat(view).doesNotContain("##");
        assertThat(view.indexOf("doA();")).isLessThan(view.indexOf("doB();"));
    }

    @Test
    void mimeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/x-typescript-code");
        assertThat(provider.language()).isEqualTo("typescript");
    }
}
