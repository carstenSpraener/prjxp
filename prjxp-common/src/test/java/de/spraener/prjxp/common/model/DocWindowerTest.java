package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 03: Doc-Chunk-Windowing. Unit tests for {@link DocWindower#windowDoc}.
 * Defaults mirror the Spring property defaults: docMaxChars=2000, docWindowChars=500.
 */
class DocWindowerTest {

    private static final int DOC_MAX = 2000;
    private static final int DOC_WINDOW = 500;

    private static String window(String content, String query, String file, String mimeType) {
        return DocWindower.windowDoc(content, query, file, mimeType, DOC_MAX, DOC_WINDOW);
    }

    // 1. Code-Chunk (text/x-java-code, 8 KB) -> unveraendert
    @Test
    void codeChunkIsUnchanged() {
        String content = "int x = 1;\n".repeat(2000);   // 8000 chars
        assertThat(window(content, "x", "src/A.java", "text/x-java-code")).isEqualTo(content);
    }

    @Test
    void allCodeMimeTypesAreUnchanged() {
        String content = "code\n".repeat(1000);         // 5000 chars > docMaxChars
        for (String mime : new String[]{"text/x-java-code", "text/x-typescript-code",
                "text/x-csharp-code", "text/x-visualbasic-code", "text/x-gdscript-code"}) {
            assertThat(window(content, "code", "f", mime)).as(mime).isEqualTo(content);
        }
    }

    // 2. Size-Budget: Markdown 8 KB, Query kommt vor -> <= 2*docWindowChars + 200, enthaelt den Match
    @Test
    void docChunkWithMatchStaysWithinBudgetAndContainsMatch() {
        String filler = "lorem ipsum dolor\n".repeat(250);              // 4500 chars
        String content = filler + "UNIQUE_TOKEN" + filler;              // ~9018 chars
        String result = window(content, "UNIQUE_TOKEN", "d.md", "text/markdown");

        assertThat(result).contains("UNIQUE_TOKEN");
        assertThat(result.length())
                .as("window + prefix/suffix + marker must fit the budget")
                .isLessThanOrEqualTo(2 * DOC_WINDOW + 200);
    }

    // 3. Markdown 8 KB, Query NICHT literal enthalten -> erste 2000 Zeichen + " [...]"
    @Test
    void docChunkWithoutLiteralMatchTakesFirstDocMaxChars() {
        String content = "abcdefghij\n".repeat(800);                    // 8800 chars, no "ZZZ"
        String result = window(content, "ZZZ", "docs/readme.md", "text/markdown");

        assertThat(result).startsWith(content.substring(0, DOC_MAX) + " [...]");
        assertThat(result).contains("readFile(\"docs/readme.md\")");
    }

    // 4. Markdown 1 KB (< docMaxChars) -> unveraendert, kein Marker
    @Test
    void smallDocIsUnchangedWithoutMarker() {
        String content = "x\n".repeat(500);                            // 1000 chars < 2000
        String result = window(content, "x", "docs/readme.md", "text/markdown");

        assertThat(result).isEqualTo(content);
        assertThat(result).doesNotContain("readFile");
    }

    // 5. Marker-Text enthaelt exakt readFile("<file>") mit dem Chunk-Pfad
    @Test
    void markerContainsExactReadFileWithChunkPath() {
        String content = "some line\n".repeat(500);                    // 5000 chars > docMaxChars
        String file = "docs/guide/chapter.md";
        String result = window(content, "some", file, "text/markdown");

        assertThat(result).contains("readFile(\"" + file + "\")");
    }

    // 6. Match am Content-Anfang/Ende -> Clamping, keine negativen Indizes
    @Test
    void matchAtContentStartClampsWithoutPrefix() {
        String content = "MATCH\n" + "x\n".repeat(4000);               // match on first line
        String result = window(content, "MATCH", "d.md", "text/markdown");

        assertThat(result).doesNotStartWith("[...]");
        assertThat(result).startsWith("MATCH");
        assertThat(result).contains("readFile(\"d.md\")");
    }

    @Test
    void matchAtContentEndClampsWithoutSuffix() {
        String content = "x\n".repeat(4000) + "MATCH";                 // match at the very end
        String result = window(content, "MATCH", "d.md", "text/markdown");

        assertThat(result).doesNotEndWith(" [...]");
        assertThat(result).contains("MATCH");
        assertThat(result).contains("readFile(\"d.md\")");
    }

    // Defensive edges
    @Test
    void nullAndEmptyContentPassThrough() {
        assertThat(window(null, "q", "f", "text/markdown")).isNull();
        assertThat(window("", "q", "f", "text/markdown")).isEmpty();
    }

    @Test
    void nullMimeTypeIsTreatedAsDoc() {
        String content = "abcdefghij\n".repeat(800);
        String result = window(content, "ZZZ", "f", null);
        assertThat(result).startsWith(content.substring(0, DOC_MAX) + " [...]");
    }

    @Test
    void matchIsFoundCaseInsensitively() {
        String filler = "lorem ipsum dolor\n".repeat(250);
        String content = filler + "Unique_Token" + filler;
        String result = window(content, "unique_token", "d.md", "text/markdown");
        assertThat(result).contains("Unique_Token");
    }
}
