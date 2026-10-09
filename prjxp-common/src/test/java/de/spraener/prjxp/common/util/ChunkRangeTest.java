package de.spraener.prjxp.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChunkRange}.
 */
class ChunkRangeTest {

    @Test
    void toCode_joinsInclusiveRange() {
        ChunkRange range = new ChunkRange(0, 2, List.of("l1", "l2", "l3"));

        assertThat(range.toCode()).isEqualTo("l1\nl2\nl3\n");
    }

    @Test
    void emptyConstant_returnsEmptyString() {
        assertThat(ChunkRange.EMPTY.toCode()).isEmpty();
    }
}
