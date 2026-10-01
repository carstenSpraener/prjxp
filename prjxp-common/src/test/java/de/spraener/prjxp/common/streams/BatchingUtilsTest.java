package de.spraener.prjxp.common.streams;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BatchingUtils}.
 */
class BatchingUtilsTest {

    @Test
    void pack_exactMultiple() {
        List<List<Integer>> batches = BatchingUtils.pack(Stream.of(1, 2, 3, 4), 2)
                .collect(Collectors.toList());

        assertThat(batches).containsExactly(List.of(1, 2), List.of(3, 4));
    }

    @Test
    void pack_remainder() {
        List<List<Integer>> batches = BatchingUtils.pack(Stream.of(1, 2, 3, 4, 5), 2)
                .collect(Collectors.toList());

        assertThat(batches).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
    }

    @Test
    void pack_emptyStream() {
        List<List<Integer>> batches = BatchingUtils.pack(Stream.<Integer>empty(), 2)
                .collect(Collectors.toList());

        assertThat(batches).isEmpty();
    }

    @Test
    void pack_batchLargerThanSource() {
        List<List<Integer>> batches = BatchingUtils.pack(Stream.of(1), 5)
                .collect(Collectors.toList());

        assertThat(batches).containsExactly(List.of(1));
    }

    /** Covers the implicit default constructor of this static utility class. */
    @Test
    void implicitConstructor_isAccessible() {
        assertThat(new BatchingUtils()).isNotNull();
    }
}
