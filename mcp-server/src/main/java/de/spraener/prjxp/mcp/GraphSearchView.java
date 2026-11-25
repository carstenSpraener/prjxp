package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;

import java.util.List;

/**
 * Result of the {@code graphSearch} MCP tool: a bounded subgraph found by a lexical
 * keyword match (seeds) and a BFS/DFS traversal, not a single-node
 * {@link GraphView}.
 *
 * <p>{@code totalNodes} / {@code totalEdges} are the sizes of the untrimmed subgraph;
 * when the token budget trimmed it, {@code truncated} is {@code true}.
 */
public record GraphSearchView(
        String project,
        List<String> seeds,
        List<GraphSearchNode> nodes,
        List<GraphSearchEdge> edges,
        String error,
        int totalNodes,
        int totalEdges,
        boolean truncated) {

    /** One node of the result subgraph; {@code method} marks method/constructor nodes. */
    public record GraphSearchNode(String id, String file, boolean method,
                                  String clusterId, Double weight) {
    }

    /** One typed edge of the result subgraph. */
    public record GraphSearchEdge(String source, String target, EdgeType type, boolean external) {
    }

    public static GraphSearchView unavailable(String message) {
        return new GraphSearchView(null, List.of(), List.of(), List.of(), message, 0, 0, false);
    }
}
