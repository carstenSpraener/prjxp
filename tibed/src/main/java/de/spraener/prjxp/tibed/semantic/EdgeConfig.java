package de.spraener.prjxp.tibed.semantic;

/** Tunables for semantic edge construction. */
public record EdgeConfig(int k, double minScore, boolean requireMutual) {

    public static EdgeConfig defaults() {
        return new EdgeConfig(20, 0.85, true);
    }
}
