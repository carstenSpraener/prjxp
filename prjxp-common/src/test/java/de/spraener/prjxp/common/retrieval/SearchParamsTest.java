package de.spraener.prjxp.common.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 01 (Graphify-Tool-V2): the ContentLevel knob on SearchParams.
 */
class SearchParamsTest {

    @Test
    void defaultEffectiveContentLevelIsFull() {
        assertThat(new SearchParams(8, 0.85).effectiveContentLevel()).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void skeletonsOnlyMapsToSkeleton() {
        assertThat(new SearchParams(8, 0.85, true).effectiveContentLevel()).isEqualTo(ContentLevel.SKELETON);
    }

    @Test
    void explicitContentLevelWinsOverSkeletonsOnly() {
        SearchParams params = new SearchParams(8, 0.85, true);
        params.setContentLevel(ContentLevel.POINTERS);

        assertThat(params.effectiveContentLevel()).isEqualTo(ContentLevel.POINTERS);
    }

    @Test
    void explicitFullWithSkeletonsOnlyFallsBackToSkeleton() {
        // contentLevel == FULL means "not set" — the legacy flag still applies
        SearchParams params = new SearchParams(8, 0.85, true);
        params.setContentLevel(ContentLevel.FULL);

        assertThat(params.effectiveContentLevel()).isEqualTo(ContentLevel.SKELETON);
    }

    @Test
    void explicitSkeletonWithSkeletonsOnlyFalseIsSkeleton() {
        SearchParams params = new SearchParams(8, 0.85, false);
        params.setContentLevel(ContentLevel.SKELETON);

        assertThat(params.effectiveContentLevel()).isEqualTo(ContentLevel.SKELETON);
    }

    @Test
    void explicitNamesWinsOverSkeletonsOnly() {
        SearchParams params = new SearchParams(8, 0.85, true);
        params.setContentLevel(ContentLevel.NAMES);

        assertThat(params.effectiveContentLevel()).isEqualTo(ContentLevel.NAMES);
    }

    // ---- ContentLevel.parse (null-safe, case-insensitive) ----

    @Test
    void parseNullReturnsFull() {
        assertThat(ContentLevel.parse(null)).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseBlankReturnsFull() {
        assertThat(ContentLevel.parse("   ")).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseUnknownReturnsFull() {
        assertThat(ContentLevel.parse("nonsense")).isEqualTo(ContentLevel.FULL);
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertThat(ContentLevel.parse("full")).isEqualTo(ContentLevel.FULL);
        assertThat(ContentLevel.parse("  Skeleton ")).isEqualTo(ContentLevel.SKELETON);
        assertThat(ContentLevel.parse("pointers")).isEqualTo(ContentLevel.POINTERS);
        assertThat(ContentLevel.parse("names")).isEqualTo(ContentLevel.NAMES);
        assertThat(ContentLevel.parse("aggregated")).isEqualTo(ContentLevel.AGGREGATED);
    }

    @Test
    void explicitAggregatedWinsOverSkeletonsOnly() {
        // Efficiency measure 5: AGGREGATED is a first-class level (group-and-count, not enumerate)
        SearchParams params = new SearchParams(8, 0.85, true);
        params.setContentLevel(ContentLevel.AGGREGATED);

        assertThat(params.effectiveContentLevel()).isEqualTo(ContentLevel.AGGREGATED);
    }
}
