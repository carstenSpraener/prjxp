package de.spraener.prjxp.tibed.semantic;

/** Tunables for semantic edge construction. */
public record EdgeConfig(int k, double minScore, boolean requireMutual, int parallelism) {

    /** Backward-compatible: parallelism 0 = auto (available processors). */
    public EdgeConfig(int k, double minScore, boolean requireMutual) {
        this(k, minScore, requireMutual, 0);
    }

    public static EdgeConfig defaults() {
        return new EdgeConfig(20, 0.85, true, 0);
    }
}
