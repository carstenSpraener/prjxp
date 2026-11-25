package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.graph.GraphRerank;
import de.spraener.prjxp.common.model.PxChunk;
import org.springframework.stereotype.Component;

/**
 * Additive modulator based on the Leiden weight (degree centrality from Phase 02 —
 * high = central node). {@code supports} mirrors "is there a weight at all";
 * a missing weight means the strategy does not fire and the old base ranking
 * is kept exactly.
 */
@Component
public class LeidenWeightRankingStrategy implements ChunkRankingStrategy {
    @Override
    public boolean supports(PxChunk chunk) {
        return chunk != null && chunk.getMetadata() != null
                && chunk.getMetadata().containsKey(GraphRerank.MDKEY_LEIDEN_WEIGHT);
    }

    @Override
    public double rank(PxChunk chunk) {
        String w = chunk.getMetadata().get(GraphRerank.MDKEY_LEIDEN_WEIGHT);
        if (w == null || w.isBlank()) return 0.0;
        try {
            return Double.parseDouble(w.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public boolean isModulator() {
        return true;
    }
}
