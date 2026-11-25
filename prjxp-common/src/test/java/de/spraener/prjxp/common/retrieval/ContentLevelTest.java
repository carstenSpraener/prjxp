package de.spraener.prjxp.common.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the parse fallbacks: the single-arg parse keeps {@link ContentLevel#FULL} for the
 * non-MCP flows (graphFile/graphNode), while the two-arg overload lets search tools pin their
 * cheap default so that a typo can never escalate to the most expensive mode.
 */
class ContentLevelTest {

    // ---- parse(String): legacy FULL fallback (graphFile/graphNode depend on it) ----

    @Test
    void parseNullDefaultsToFull() {
        assertThat(ContentLevel.parse(null)).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseBlankDefaultsToFull() {
        assertThat(ContentLevel.parse("   ")).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseUnknownDefaultsToFull() {
        assertThat(ContentLevel.parse("quatsch")).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseKnownValuesCaseInsensitive() {
        assertThat(ContentLevel.parse("full")).isEqualTo(ContentLevel.FULL);
        assertThat(ContentLevel.parse("  Skeleton ")).isEqualTo(ContentLevel.SKELETON);
        assertThat(ContentLevel.parse("pointers")).isEqualTo(ContentLevel.POINTERS);
        assertThat(ContentLevel.parse("names")).isEqualTo(ContentLevel.NAMES);
        assertThat(ContentLevel.parse("aggregated")).isEqualTo(ContentLevel.AGGREGATED);
    }

    // ---- parse(String, ContentLevel): explicit fallback for search tools ----

    @Test
    void parseNullUsesFallback() {
        assertThat(ContentLevel.parse(null, ContentLevel.POINTERS)).isEqualTo(ContentLevel.POINTERS);
        assertThat(ContentLevel.parse(null, ContentLevel.NAMES)).isEqualTo(ContentLevel.NAMES);
    }

    @Test
    void parseBlankUsesFallback() {
        assertThat(ContentLevel.parse("   ", ContentLevel.POINTERS)).isEqualTo(ContentLevel.POINTERS);
    }

    @Test
    void parseTypoUsesFallbackInsteadOfFull() {
        // the benchmark failure mode: "SKELETONN" must not escalate to the most expensive mode
        assertThat(ContentLevel.parse("SKELETONN", ContentLevel.POINTERS)).isEqualTo(ContentLevel.POINTERS);
        assertThat(ContentLevel.parse("quatsch", ContentLevel.NAMES)).isEqualTo(ContentLevel.NAMES);
    }

    @Test
    void parseKnownValueWinsOverFallback() {
        assertThat(ContentLevel.parse("full", ContentLevel.POINTERS)).isEqualTo(ContentLevel.FULL);
        assertThat(ContentLevel.parse("  skeleton ", ContentLevel.NAMES)).isEqualTo(ContentLevel.SKELETON);
    }
}
