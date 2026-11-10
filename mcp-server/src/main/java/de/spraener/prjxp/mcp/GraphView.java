package de.spraener.prjxp.mcp;

import java.util.List;

/**
 * Result of the {@code graphFile} / {@code graphNode} MCP tools: the typed code-graph
 * neighbourhood of one node (a file's type node or a symbol), with per-direction edge
 * lists, an optional candidate list on ambiguous resolution, and an error message when
 * the graph is unavailable.
 */
public record GraphView(String project, String file, String nodeId, String clusterId, Double weight,
                        List<GraphNeighbor> outgoing, List<GraphNeighbor> incoming,
                        List<String> candidates, String error) {

    /** A view that carries only an error message (no node, no edges). */
    public static GraphView unavailable(String message) {
        return new GraphView(null, null, null, null, null, List.of(), List.of(), List.of(), message);
    }

    /** A view for an ambiguous request: candidate ids instead of edges, no error. */
    public static GraphView ambiguous(String project, String file, List<String> candidates) {
        return new GraphView(project, file, null, null, null, List.of(), List.of(), List.copyOf(candidates), null);
    }
}
