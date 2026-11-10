package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChunkRankingService {
    private final List<ChunkRankingStrategy> strategies;
    private final GraphRerankConfig graphRerankConfig;

    /**
     * {@code wBase*baseRank + min(bGraph*weight, cap)}.
     * No modulators → {@code weight} is 0 → exactly the old base rank.
     */
    public double rank(PxChunk chunk) {
        Double base = null;
        Double weight = null;
        for (ChunkRankingStrategy strategy : strategies) {
            if (!strategy.supports(chunk)) continue;
            if (strategy.isModulator()) {
                if (weight == null) weight = strategy.rank(chunk);
            } else if (base == null) {
                base = strategy.rank(chunk);
            }
        }
        double b = base != null ? base : 0.0;
        double w = weight != null ? weight : 0.0;
        return graphRerankConfig.getWBase() * b + Math.min(graphRerankConfig.getBGraph() * w, graphRerankConfig.getCap());
    }
}
