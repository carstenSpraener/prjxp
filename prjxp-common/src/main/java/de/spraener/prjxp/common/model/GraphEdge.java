package de.spraener.prjxp.common.model;

/**
 * A directed, typed edge of the code knowledge graph.
 *
 * <p>{@code external} is {@code true} when the target is not a node declared in the
 * current run (e.g. a JDK or Spring type) — the graph tool renders internal edges by
 * default and exposes external ones on request.
 */
public record GraphEdge(
        String source,
        String target,
        EdgeType type,
        boolean external) {
}
