package de.spraener.prjxp.common.retrieval.ranking;

/**
 * One entry of a ranked signal list: a chunk ({@code id}) and the file it belongs to.
 * <p>
 * {@code score} is the signal-local score and only informational — the fused relevance is derived
 * from the rank, not from this value (see {@link RankFusion}).
 */
public record RankedItem(String id, String file, double score) {
}
