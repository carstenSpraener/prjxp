package de.spraener.prjxp.common;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PxChunkFromJsonLReaderTest {

    private final PxChunkFromJsonLReader reader = new PxChunkFromJsonLReader();

    /** Lines containing "skip" map to null, all other lines become a chunk whose content is the line. */
    private Function<String, PxChunk> mapper() {
        return line -> line.contains("skip") ? null : PxChunk.create(c -> c.setContent(line));
    }

    private static List<String> contents(PxChunk[] batch) {
        return Arrays.stream(batch).map(PxChunk::getContent).toList();
    }

    @Test
    void batched_exactAndRemainder() {
        Stream<String> lines = Stream.of("a", "b", "c", "d", "e");

        List<PxChunk[]> batches = reader.readChunksFromJsonlStreamBatched(lines, 2, mapper())
                .collect(Collectors.toList());

        assertThat(batches).hasSize(3);
        assertThat(contents(batches.get(0))).containsExactly("a", "b");
        assertThat(contents(batches.get(1))).containsExactly("c", "d");
        assertThat(contents(batches.get(2))).containsExactly("e");
    }

    @Test
    void batched_nullsFiltered_emptyBatchesDropped() {
        // The "skip" line maps to null and is filtered out of its batch; "a" and "b" survive.
        Stream<String> lines = Stream.of("a", "skip", "b");

        List<PxChunk[]> batches = reader.readChunksFromJsonlStreamBatched(lines, 2, mapper())
                .collect(Collectors.toList());

        assertThat(batches).hasSize(2);
        assertThat(contents(batches.get(0))).containsExactly("a");
        assertThat(contents(batches.get(1))).containsExactly("b");
    }

    @Test
    void batched_fullyNullBatch_isDropped() {
        // Batch 1 consists only of "skip" lines -> becomes empty after null filtering and is dropped.
        Stream<String> lines = Stream.of("skip", "skip", "b");

        List<PxChunk[]> batches = reader.readChunksFromJsonlStreamBatched(lines, 2, mapper())
                .collect(Collectors.toList());

        assertThat(batches).hasSize(1);
        assertThat(contents(batches.get(0))).containsExactly("b");
    }
}
