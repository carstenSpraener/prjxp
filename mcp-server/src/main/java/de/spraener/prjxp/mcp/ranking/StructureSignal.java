package de.spraener.prjxp.mcp.ranking;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.retrieval.ranking.RankedItem;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Structural relevance as one ranking signal: a method body is a better answer than a class frame
 * or a dependency list, independent of the query. The existing {@link ChunkRankingService} already
 * encodes that per language (base rank plus graph modulator).
 * <p>
 * The signal only <em>reorders</em> the candidates it is given — it never introduces a file the
 * lexical search did not find. A structural statement ("this is a method") says something about a
 * hit, not about whether it is a hit.
 */
@RequiredArgsConstructor
public class StructureSignal {

    /** Highest base rank of the language rankers ({@code JavaChunkRanker}: METHOD = 5). */
    static final double MAX_RANK = 5.0;

    private final ChunkRankingService rankingService;

    public List<RankedItem> rank(List<PxChunk> candidates) {
        if (candidates == null || candidates.isEmpty() || rankingService == null) {
            return List.of();
        }
        List<RankedItem> items = new ArrayList<>(candidates.size());
        for (PxChunk chunk : candidates) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            double score = rankingService.rank(chunk);
            if (score <= 0.0) {
                continue;   // e.g. DEPENDENCIE_INFO: no structural relevance at all
            }
            items.add(new RankedItem(chunk.getId(), chunk.getFile(), score / MAX_RANK));
        }
        items.sort(Comparator.comparingDouble(RankedItem::score).reversed()
                .thenComparing(RankedItem::file, Comparator.nullsLast(String::compareTo))
                .thenComparing(RankedItem::id));
        return items;
    }
}
