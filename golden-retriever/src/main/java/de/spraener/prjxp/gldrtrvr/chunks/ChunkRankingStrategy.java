package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.model.PxChunk;

public interface ChunkRankingStrategy {
    boolean supports(PxChunk chunk);
    double rank(PxChunk chunk);
}
