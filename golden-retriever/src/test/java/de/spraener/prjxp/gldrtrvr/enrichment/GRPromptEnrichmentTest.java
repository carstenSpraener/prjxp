package de.spraener.prjxp.gldrtrvr.enrichment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GRPromptEnrichmentTest {

    private final GRPromptEnrichment enrichment = new GRPromptEnrichment(null, List.of());

    @Test
    void reIterateSnapsOffGridThresholdToNextLowerGridPoint() {
        SearchParams params = new SearchParams(20, 0.93);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.90, offset(1e-9));
        assertThat(params.getEffectiveMinScore()).isCloseTo(0.90, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(1);
    }

    @Test
    void reIterateOnGridThresholdMovesToNextLowerPoint() {
        SearchParams params = new SearchParams(20, 0.85);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.80, offset(1e-9));
    }

    @Test
    void reIterateSequenceFollowsCanonicalGrid() {
        SearchParams params = new SearchParams(20, 0.97);

        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.95, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.90, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.85, offset(1e-9));
        enrichment.reIterate(params);
        assertThat(params.getMinScore()).isCloseTo(0.80, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(4);
    }

    @Test
    void reIterateAbortsBelowHalf() {
        SearchParams params = new SearchParams(20, 0.50);

        enrichment.reIterate(params);

        assertThat(params.getMinScore()).isCloseTo(0.45, offset(1e-9));
        assertThat(params.isAbort()).isTrue();
    }

    @Test
    void reIterateStillGrowsMaxResultBelow16() {
        SearchParams params = new SearchParams(8, 0.93);

        enrichment.reIterate(params);

        assertThat(params.getMaxResult()).isEqualTo(10);
        assertThat(params.getMinScore()).isCloseTo(0.93, offset(1e-9));
        assertThat(params.getFallbackRounds()).isEqualTo(0);
    }
}
