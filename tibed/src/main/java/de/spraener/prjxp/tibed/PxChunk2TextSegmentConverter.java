package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

public class PxChunk2TextSegmentConverter {
    public static TextSegment convert(PxChunk chunk) {
        Metadata metadata = new Metadata();
        PxChunk.metadataAsMap(chunk).forEach(metadata::put);
        String content = chunk.getContent();
        return TextSegment.from(content, metadata);
    }

    public static TextSegment convertWithEmbeddingPrefix(PxChunk chunk) {
        Metadata metadata = new Metadata();
        PxChunk.metadataAsMap(chunk).forEach(metadata::put);
        String content = chunk.getContent();
        if( chunk.getEmbeddingPrefix()!=null && !chunk.getEmbeddingPrefix().isBlank() ) {
            content = chunk.getEmbeddingPrefix() + "\n" + content;
        }
        return TextSegment.from(content, metadata);
    }
}
