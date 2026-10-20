package de.spraener.prjxp.mcp;

public final class SearchLimits {

    public static final int DEFAULT_LIMIT = 10;
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
