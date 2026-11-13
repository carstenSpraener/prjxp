package de.spraener.prjxp.common.retrieval;

/**
 * How much content the enrichment emits for a hit.
 * <ul>
 *   <li>{@link #FULL}       — full method bodies on hit (current default for all non-MCP flows).</li>
 *   <li>{@link #SKELETON}   — class skeletons / method signatures only.</li>
 *   <li>{@link #POINTERS}   — no content; only the tool-hint layer (Phase 02). In Phase 01 it renders like {@link #SKELETON}.</li>
 *   <li>{@link #NAMES}      — no content, no hints; only the file name per hit (efficiency measure 1).
 *       The cheapest enumeration for "where is X used" questions — one line per distinct file.</li>
 *   <li>{@link #AGGREGATED} — group and count instead of enumerate (efficiency measure 5).
 *       For search tools (grep/vectorSearch) it behaves like {@link #NAMES} (one line per distinct file).
 *       For graph tools (graphNode/graphFile) it aggregates the edges by module: per-module edge
 *       count, distinct-class count, edge-type breakdown, and the top classes — the cheap answer
 *       to "which modules/classes use X" without a raw edge list.</li>
 * </ul>
 */
public enum ContentLevel {
    FULL, SKELETON, POINTERS, NAMES, AGGREGATED;

    /** Null-safe parse; unknown/blank → {@link #FULL}. */
    public static ContentLevel parse(String raw) {
        if (raw == null || raw.isBlank()) return FULL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FULL;
        }
    }
}
