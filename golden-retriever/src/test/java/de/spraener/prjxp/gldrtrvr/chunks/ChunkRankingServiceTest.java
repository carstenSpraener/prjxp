package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkRankingServiceTest {

    private static final LeidenWeightRankingStrategy LEIDEN = new LeidenWeightRankingStrategy();

    /** Base strategy with fixed support/rank — stands in for the language rankers. */
    private record FixedBaseStrategy(boolean supports, double rank) implements ChunkRankingStrategy {
        @Override
        public boolean supports(PxChunk chunk) {
            return supports;
        }

        @Override
        public double rank(PxChunk chunk) {
            return rank;
        }
    }

    /** Modulator with fixed support/rank — stands in for a second weight source. */
    private record FixedModulator(boolean supports, double rank) implements ChunkRankingStrategy {
        @Override
        public boolean supports(PxChunk chunk) {
            return supports;
        }

        @Override
        public double rank(PxChunk chunk) {
            return rank;
        }

        @Override
        public boolean isModulator() {
            return true;
        }
    }

    private static PxChunk chunkWithWeight(String weight) {
        return PxChunk.create(c -> c.getMetadata().put(GraphMetadata.LEIDEN_WEIGHT, weight));
    }

    private static PxChunk sectionChunk() {
        return PxChunk.create(c -> c.getMetadata().put("java_code_section", "method"));
    }

    @Test
    void sectionAndWeightCombineAdditively() {
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(true, 5.0), LEIDEN), new GraphRerankConfig());

        assertThat(service.rank(chunkWithWeight("0.5"))).isEqualTo(7.5);   // 1.0*5 + min(5*0.5, 5)
    }

    @Test
    void sectionOnlyKeepsOldRankExactly() {
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(true, 5.0), LEIDEN), new GraphRerankConfig());

        assertThat(service.rank(sectionChunk())).isEqualTo(5.0);   // no weight -> exactly the old base rank
    }

    @Test
    void weightOnlyYieldsCappedBonus() {
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(false, 5.0), LEIDEN), new GraphRerankConfig());

        assertThat(service.rank(chunkWithWeight("1.0"))).isEqualTo(5.0);   // min(5.0, 5.0)
    }

    @Test
    void capLimitsTheBonus() {
        GraphRerankConfig cfg = new GraphRerankConfig();
        cfg.setCap(2.0);
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(false, 0.0), LEIDEN), cfg);

        assertThat(service.rank(chunkWithWeight("1.0"))).isEqualTo(2.0);
    }

    @Test
    void wBaseScalesTheBaseRank() {
        GraphRerankConfig cfg = new GraphRerankConfig();
        cfg.setWBase(0.5);
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(true, 5.0), LEIDEN), cfg);

        assertThat(service.rank(sectionChunk())).isEqualTo(2.5);
    }

    @Test
    void noStrategySupportsReturnsZero() {
        var service = new ChunkRankingService(List.of(), new GraphRerankConfig());

        assertThat(service.rank(PxChunk.create(c -> c.setContent("x")))).isEqualTo(0.0);
    }

    @Test
    void firstBaseAndFirstModulatorWin() {
        var service = new ChunkRankingService(
                List.of(new FixedBaseStrategy(true, 5.0), new FixedBaseStrategy(true, 100.0),
                        LEIDEN, new FixedModulator(true, 2.0)),
                new GraphRerankConfig());

        // base 5 (not 100) + weight 0.5 (not 2.0) -> 1.0*5 + min(5*0.5, 5) = 7.5
        assertThat(service.rank(chunkWithWeight("0.5"))).isEqualTo(7.5);
    }

    @Test
    void centralBeatsPeripheral() {
        var service = new ChunkRankingService(List.of(new FixedBaseStrategy(true, 5.0), LEIDEN), new GraphRerankConfig());

        double central = service.rank(chunkWithWeight("1.0"));
        double peripheral = service.rank(chunkWithWeight("0.0"));

        assertThat(central).isGreaterThan(peripheral);
    }
}
