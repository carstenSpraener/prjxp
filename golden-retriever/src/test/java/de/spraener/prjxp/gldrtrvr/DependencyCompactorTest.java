package de.spraener.prjxp.gldrtrvr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 01: the dependency section of the prompt is the biggest bloat factor of vectorSearch
 * answers (~40 FQNs per class, ~30 of them test classes). {@link DependencyCompactor} keeps only
 * the first N entries (Outgoing + Incoming combined) and appends a counter hint.
 */
public class DependencyCompactorTest {

    public static final String MARKER_30 = "[30 weitere Dependencies nicht angezeigt]";

    public static String fqn(String prefix, int i) {
        return "com.example.module.something." + prefix + i;
    }

    /**
     * Dependency-chunk format as produced by JavaDependenciesChunker:
     * "## Dependencies of <fqn>:\n\n**Outgoing**\n  * <fqn>\n...\n\n**Incoming**:\n  * <fqn>\n..."
     */
    public static String dependencyContent(int outgoing, int incoming) {
        StringBuilder sb = new StringBuilder("## Dependencies of com.example.Foo:\n\n**Outgoing**\n");
        for (int i = 0; i < outgoing; i++) {
            sb.append("  * ").append(fqn("Outgoing", i)).append('\n');
        }
        sb.append("\n**Incoming**:\n");
        for (int i = 0; i < incoming; i++) {
            sb.append("  * ").append(fqn("Incoming", i)).append('\n');
        }
        return sb.toString();
    }

    public static int countEntries(String content) {
        return (int) content.lines().filter(l -> l.startsWith("  * ")).count();
    }

    @Test
    void compactsToMaxEntriesKeepsOrderAndHeadersAndAppendsCounter() {
        String content = dependencyContent(25, 15); // 40 entries

        String result = DependencyCompactor.compact(content, 10);

        assertThat(countEntries(result)).isEqualTo(10);
        assertThat(result).contains(MARKER_30);
        // Phase 02: neutral marker — no tool name (grep) in the prompt
        assertThat(result).doesNotContain("grep");
        assertThat(result).contains("**Outgoing**");
        assertThat(result).contains("**Incoming**:");
        // original order: the first 10 entries are the first 10 outgoing ones
        assertThat(result).contains(fqn("Outgoing", 0));
        assertThat(result).contains(fqn("Outgoing", 9));
        assertThat(result).doesNotContain(fqn("Outgoing", 10));
        assertThat(result).doesNotContain(fqn("Incoming", 0));
    }

    @Test
    void compactedContentStaysWithinSizeBudget() {
        String content = dependencyContent(25, 15); // ~2 KB
        assertThat(content.length()).isGreaterThan(1500);

        String result = DependencyCompactor.compact(content, 10);

        assertThat(result.length()).isLessThanOrEqualTo(600);
    }

    @Test
    void fewerEntriesThanMaxAreReturnedUnchangedWithoutMarker() {
        String content = dependencyContent(3, 2);

        String result = DependencyCompactor.compact(content, 10);

        assertThat(result).isEqualTo(content);
        assertThat(result).doesNotContain("weitere Dependencies");
    }

    @Test
    void zeroMaxEntriesReturnsUnchanged() {
        String content = dependencyContent(25, 15);

        assertThat(DependencyCompactor.compact(content, 0)).isEqualTo(content);
    }

    @Test
    void negativeMaxEntriesReturnsUnchanged() {
        String content = dependencyContent(25, 15);

        assertThat(DependencyCompactor.compact(content, -1)).isEqualTo(content);
    }

    @Test
    void nullContentReturnsNull() {
        assertThat(DependencyCompactor.compact(null, 10)).isNull();
    }

    @Test
    void contentWithoutEntriesIsReturnedUnchanged() {
        String content = "## Dependencies of com.example.Foo:\n\n**Outgoing**\n\n**Incoming**:\n";

        assertThat(DependencyCompactor.compact(content, 10)).isEqualTo(content);
    }
}
