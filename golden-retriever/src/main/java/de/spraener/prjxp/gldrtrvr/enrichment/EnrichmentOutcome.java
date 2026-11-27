package de.spraener.prjxp.gldrtrvr.enrichment;

/**
 * The objective result of one enrichment run (Phase 02: Vektor- und Lexikal-Treffer trennen).
 * <p>
 * The threshold ladder is a property of the <em>vector</em> channel, so the outcome reports the
 * two channels separately: {@code vectorHits} are the hits that passed the cosine threshold,
 * {@code lexicalHits} the exact-token (BM25) hits, which are never thresholded. Callers that
 * need the historical string contract (REST) read {@link #context()}; callers that need an
 * honest header (the MCP tool) format it from the counts.
 * <p>
 * {@code bestVectorScore} is the maximum of the vector-channel scores after the includeTests
 * filter (Phase 03b: the confidence gate compares against it); {@code 0.0} when there is no
 * vector hit.
 * <p>
 * {@code context} carries <em>no</em> header line of its own: on success it is the formatted
 * prompt, on abort the historical "Es konnte kein valider Kontext erstellt werden! …" message.
 */
public record EnrichmentOutcome(
        String context,
        int vectorHits,
        int lexicalHits,
        double bestVectorScore,
        double effectiveMinScore,
        int fallbackRounds,
        boolean aborted) {

    /** True when at least one vector-channel hit (after the includeTests filter) was found. */
    public boolean hasVectorHit() {
        return vectorHits > 0;
    }

    /** True when the rendered context is missing or blank. */
    public boolean isEmpty() {
        return context == null || context.isBlank();
    }
}
