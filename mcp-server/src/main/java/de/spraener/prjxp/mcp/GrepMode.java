package de.spraener.prjxp.mcp;

import java.util.Locale;

/**
 * Matching strategy of the {@code grep} tool.
 * <p>
 * {@code EXACT} matches the query as a whole word, {@code SUBSTRING} as a literal substring and
 * {@code REGEX} as a Java regular expression. All three scan the indexed chunk content line by
 * line, so every hit carries a {@code file:line} anchor — the information the model needs to cite
 * and to read on deliberately.
 */
public enum GrepMode {
    EXACT, SUBSTRING, REGEX;

    public static GrepMode parse(String raw, GrepMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
