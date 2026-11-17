package de.spraener.prjxp.mcp;

public final class SearchLimits {

    /**
     * Default max files for grep. Raised 10 → 30: the NAMES output is one line per file
     * (~300 tokens for 30 lines), which is cheaper than a second search run — and a limit of
     * 10 silently cut off targets that ranked 11th or lower (benchmark: ChunkRankingService).
     */
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;

    /** listFiles: file paths are cheap per entry, so the default/max are higher than for content hits. */
    public static final int LIST_DEFAULT_LIMIT = 50;
    public static final int LIST_MAX_LIMIT = 500;

    private SearchLimits() {
    }

    public static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    public static int clampList(int limit) {
        return Math.max(1, Math.min(limit, LIST_MAX_LIMIT));
    }
}
