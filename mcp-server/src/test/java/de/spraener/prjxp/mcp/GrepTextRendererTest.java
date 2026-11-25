package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrepTextRendererTest {

    private static SearchHit hit(long count, String file) {
        return new SearchHit(file, count, file, null, null, count + "  " + file, "grep", Map.of());
    }

    @Test
    void namesRendersOneLinePerFileWithoutJsonEnvelope() {
        String text = GrepTextRenderer.render(GrepResult.of(List.of(
                hit(9, "mcp-server/src/main/java/FileGraphService.java"),
                hit(4, "prjxp-common/src/main/java/ProjectRegistry.java")), 2), ContentLevel.NAMES);

        assertThat(text).isEqualTo("""
                9  mcp-server/src/main/java/FileGraphService.java
                4  prjxp-common/src/main/java/ProjectRegistry.java
                """);
        assertThat(text).doesNotContain("{").doesNotContain("\"").doesNotContain("chunkId");
    }

    @Test
    void namesAppendsTruncationNote() {
        String text = GrepTextRenderer.render(GrepResult.of(List.of(hit(9, "A.java")), 3), ContentLevel.NAMES);

        assertThat(text).startsWith("9  A.java\n").contains("showing 1 of 3").contains("2 more not shown");
    }

    @Test
    void aggregatedBehavesLikeNames() {
        String text = GrepTextRenderer.render(GrepResult.of(List.of(hit(2, "A.java")), 1), ContentLevel.AGGREGATED);

        assertThat(text).isEqualTo("2  A.java\n");
    }

    @Test
    void otherLevelsKeepTheJsonContract() {
        String text = GrepTextRenderer.render(GrepResult.of(List.of(hit(1, "A.java")), 1), ContentLevel.FULL);

        assertThat(text).startsWith("{").contains("\"hits\"").contains("\"totalFiles\"");
    }

    @Test
    void emptyNamesResultIsNotEmptyText() {
        assertThat(GrepTextRenderer.render(GrepResult.of(List.of(), 0), ContentLevel.NAMES))
                .isEqualTo("No matches.\n");
    }

    @Test
    void emptyNamesResultWithQueryHintsAtExactTokenSemantics() {
        // No silent dead end: grep matches whole tokens, so a prefix of a symbol name
        // finds nothing — the answer must point at the full name or listFiles
        assertThat(GrepTextRenderer.render(GrepResult.of(List.of(), 0), ContentLevel.NAMES, "ChunkRanking"))
                .contains("No matches")
                .contains("Exact token")
                .contains("listFiles(\"*ChunkRanking*\")");
    }
}
