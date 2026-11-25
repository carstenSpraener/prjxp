package de.spraener.prjxp.common.graph;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GraphRerankTest {

    private static PxChunk chunkWithWeight(String weight) {
        return PxChunk.create(c -> c.getMetadata().put(GraphMetadata.LEIDEN_WEIGHT, weight));
    }

    @Test
    void weightOfDefaultsToOneWithoutMetadata() {
        PxChunk chunk = PxChunk.create(c -> c.setContent("no metadata key"));

        assertThat(GraphRerank.weightOf(chunk)).isEqualTo(1.0);
    }

    @Test
    void weightOfReadsValue() {
        assertThat(GraphRerank.weightOf(chunkWithWeight("0.25"))).isEqualTo(0.25);
    }

    @Test
    void weightOfIsOneForBlankAndInvalid() {
        assertThat(GraphRerank.weightOf(chunkWithWeight(""))).isEqualTo(1.0);
        assertThat(GraphRerank.weightOf(chunkWithWeight("  "))).isEqualTo(1.0);
        assertThat(GraphRerank.weightOf(chunkWithWeight("abc"))).isEqualTo(1.0);
    }

    @Test
    void weightOfIsOneForNullChunk() {
        assertThat(GraphRerank.weightOf(null)).isEqualTo(1.0);
    }

    @Test
    void rerankIsNeutralForWeightOne() {
        assertThat(GraphRerank.rerank(0.9, chunkWithWeight("1.0"), 0.8)).isEqualTo(0.9);
    }

    @Test
    void rerankAppliesFloorForWeightZero() {
        assertThat(GraphRerank.rerank(0.9, chunkWithWeight("0.0"), 0.8)).isCloseTo(0.72, offset(1e-9));
    }

    @Test
    void rerankIsLinearBetween() {
        assertThat(GraphRerank.rerank(0.9, chunkWithWeight("0.5"), 0.8)).isCloseTo(0.81, offset(1e-9));
    }

    @Test
    void floorOneLeavesScoreUntouched() {
        assertThat(GraphRerank.rerank(0.9, chunkWithWeight("0.0"), 1.0)).isEqualTo(0.9);
    }

    @Test
    void rerankWithoutWeightLeavesScoreUntouched() {
        PxChunk chunk = PxChunk.create(c -> c.setContent("no metadata at all"));

        assertThat(GraphRerank.rerank(0.9, chunk, 0.8)).isEqualTo(0.9);
    }
}
