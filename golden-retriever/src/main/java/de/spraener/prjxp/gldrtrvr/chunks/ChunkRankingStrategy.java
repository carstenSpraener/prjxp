package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.model.PxChunk;

public interface ChunkRankingStrategy {
    boolean supports(PxChunk chunk);
    double rank(PxChunk chunk);

    /**
     * A modulator contributes an additive bonus instead of being the base rank.
     * Exactly one non-modulator base strategy and at most one modulator are combined
     * by {@link ChunkRankingService}.
     */
    default boolean isModulator() { return false; }
}
