package de.spraener.prjxp.mcp.hub.dep;

/** A dependency that passed the embeddable filter (has at least one classifier). */
public record ResolvedDependency(MavenCoordinates coords, int depth, boolean hasSources, boolean hasJavadoc) {
    /** Import criterion: at least one of -sources / -javadoc must exist. */
    public boolean isEmbeddable() { return hasSources || hasJavadoc; }
}
