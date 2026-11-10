package de.spraener.prjxp.common.graph;

import de.spraener.prjxp.common.model.PxChunk;

/**
 * Multiplicative graph re-ranking used by the REST vector search.
 * <p>
 * Only this path is multiplicative: {@code similarity * (floor + (1 - floor) * weight)}.
 * The retriever path (golden-retriever) adds the weight via {@code ChunkRankingService}
 * instead, so the Leiden weight is applied exactly once per retrieval path —
 * never both additively and multiplicatively in the same path.
 */
public final class GraphRerank {
    public static final String MDKEY_LEIDEN_WEIGHT = GraphMetadata.LEIDEN_WEIGHT;

    private GraphRerank() {}

    /** Leiden weight of the chunk, or {@code 1.0} (= neutral) when absent/blank/invalid. */
    public static double weightOf(PxChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null) return 1.0;
        String w = chunk.getMetadata().get(MDKEY_LEIDEN_WEIGHT);
        if (w == null || w.isBlank()) return 1.0;
        try {
            return Double.parseDouble(w.trim());
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /** {@code similarity * (floor + (1 - floor) * weight)}; a missing weight leaves the score untouched. */
    public static double rerank(double similarity, PxChunk chunk, double floor) {
        return similarity * (floor + (1.0 - floor) * weightOf(chunk));
    }
}
