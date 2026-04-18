package de.spraener.prjxp.common;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.streams.BatchingUtils;

import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public class PxChunkFromJsonLReader {

    public Stream<PxChunk[]> readChunksFromJsonlStreamBatched(Stream<String> jsonlStream, int batchSize, Function<String, PxChunk> jsonL2Chunk) {
        return BatchingUtils.pack(jsonlStream, batchSize)
                .map(batch -> batch.stream()
                        .map(line -> jsonL2Chunk.apply(line))
                        .filter(Objects::nonNull)
                        .toList())
                .filter(list -> !list.isEmpty())
                .map(list -> list.toArray(new PxChunk[0]))
                ;
    }
}
