package de.spraener.prjxp.common.retrieval.ranking;

/**
 * A file in the fused ranking. {@code score} is the best chunk score of that file (max, never a
 * sum — otherwise large files win by chunk count alone), {@code chunks} how many chunks
 * contributed.
 */
public record RankedFile(String file, double score, int chunks) {
}
