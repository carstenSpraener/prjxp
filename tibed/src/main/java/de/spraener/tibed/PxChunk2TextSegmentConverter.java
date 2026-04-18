package de.spraener.tibed;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

public class PxChunk2TextSegmentConverter {
    public static TextSegment convert(PxChunk chunk) {
        Metadata metadata = new Metadata();
        PxChunk.metadataAsMap(chunk).forEach(metadata::put);

        return TextSegment.from(chunk.getContent(), metadata);
    }
}
