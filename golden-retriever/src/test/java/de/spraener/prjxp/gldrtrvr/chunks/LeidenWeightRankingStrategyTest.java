package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeidenWeightRankingStrategyTest {

    private final LeidenWeightRankingStrategy strategy = new LeidenWeightRankingStrategy();

    private static PxChunk chunkWithWeight(String weight) {
        return PxChunk.create(c -> c.getMetadata().put(GraphMetadata.LEIDEN_WEIGHT, weight));
    }

    @Test
    void supportsOnlyWhenWeightPresent() {
        assertThat(strategy.supports(chunkWithWeight("0.5"))).isTrue();
        assertThat(strategy.supports(PxChunk.create(c -> c.setContent("x")))).isFalse();
        assertThat(strategy.supports(PxChunk.create(c -> c.getMetadata().put(GraphMetadata.LEIDEN_CLUSTER_ID, "3")))).isFalse();
        assertThat(strategy.supports(null)).isFalse();
    }

    @Test
    void rankReturnsWeight() {
        assertThat(strategy.rank(chunkWithWeight("0.42"))).isEqualTo(0.42);
    }

    @Test
    void rankIsZeroForBlankAndInvalid() {
        assertThat(strategy.rank(chunkWithWeight(""))).isEqualTo(0.0);
        assertThat(strategy.rank(chunkWithWeight("   "))).isEqualTo(0.0);
        assertThat(strategy.rank(chunkWithWeight("abc"))).isEqualTo(0.0);
    }

    @Test
    void isModulatorIsTrue() {
        assertThat(strategy.isModulator()).isTrue();
    }
}
