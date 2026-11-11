package de.spraener.prjxp.common.retrieval;

/**
 * How much content the enrichment emits for a hit.
 * <ul>
 *   <li>{@link #FULL}     — full method bodies on hit (current default for all non-MCP flows).</li>
 *   <li>{@link #SKELETON} — class skeletons / method signatures only.</li>
 *   <li>{@link #POINTERS} — no content; only the tool-hint layer (Phase 02). In Phase 01 it renders like {@link #SKELETON}.</li>
 * </ul>
 */
public enum ContentLevel {
    FULL, SKELETON, POINTERS;

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
