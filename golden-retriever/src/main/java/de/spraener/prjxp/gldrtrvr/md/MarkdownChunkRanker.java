package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import org.springframework.stereotype.Component;

@Component
public class MarkdownChunkRanker implements ChunkRankingStrategy {
    @Override
    public boolean supports(PxChunk chunk) {
        return chunk.getMetadata().containsKey("section_number");
    }

    @Override
    public double rank(PxChunk chunk) {
        return chunk.getContent().length();
    }
}
