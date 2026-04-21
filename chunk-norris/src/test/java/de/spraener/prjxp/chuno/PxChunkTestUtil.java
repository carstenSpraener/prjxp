package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.model.PxChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class PxChunkTestUtil {
    public static String combine(List<PxChunk> chunkList, Predicate<PxChunk> filter) {
        List<PxChunk> relevantChunks = new ArrayList<>();
        chunkList.stream()
                .filter(filter)
                .forEach(c -> relevantChunks.add(c));
        PxChunk combined = PxChunk.combine(relevantChunks);
        return combined.getContent();
    }
}
