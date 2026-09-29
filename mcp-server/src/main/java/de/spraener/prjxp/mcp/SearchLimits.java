package de.spraener.prjxp.mcp;

public final class SearchLimits {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    private SearchLimits() {
    }

    public static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
