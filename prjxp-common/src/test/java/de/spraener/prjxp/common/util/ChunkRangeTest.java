package de.spraener.prjxp.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChunkRange}.
 */
class ChunkRangeTest {

    @Test
    void toCode_joinsRangeFromInclusiveToExclusive() {
        ChunkRange range = new ChunkRange(0, 2, List.of("l1", "l2", "l3"));

        // toLine is the numeric value of the last 1-based line, i.e. the exclusive 0-based end.
        assertThat(range.toCode()).isEqualTo("l1\nl2\n");
    }

    @Test
    void toCode_clampsToAvailableLines() {
        ChunkRange range = new ChunkRange(1, 99, List.of("l1", "l2"));

        assertThat(range.toCode()).isEqualTo("l2\n");
    }

    @Test
    void emptyConstant_returnsEmptyString() {
        assertThat(ChunkRange.EMPTY.toCode()).isEmpty();
    }
}
