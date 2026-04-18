package de.spraener.gldrtrvr.chunks;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

public class TextSegment2PxChunkConverter {
    public static PxChunk convert(EmbeddingMatch<TextSegment> textSegmentEmbeddingMatch) {
        TextSegment segment = textSegmentEmbeddingMatch.embedded();
        return PxChunk.fromContentAndMap(segment.text(), segment.metadata().toMap());
    }
}
