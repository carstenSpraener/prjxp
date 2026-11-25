package de.spraener.prjxp.common.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An immutable code knowledge graph: nodes (types and methods) plus typed edges.
 *
 * <p>Built from the AST by a {@link de.spraener.prjxp.common.language.CodeGraphBuilder},
 * then rated (cluster + weight) by a {@link de.spraener.prjxp.common.language.GraphWeighter}.
 * Serialized to {@code graph.json} and consumed by the MCP {@code graph} tool and the
 * retriever's re-ranking — fully orthogonal to the embedding/Lucene index.
 */
public record CodeGraph(List<GraphNode> nodes, List<GraphEdge> edges) {

    public static CodeGraph empty() {
        return new CodeGraph(List.of(), List.of());
    }

    /** Nodes indexed by id (first wins on duplicate ids). */
    public Map<String, GraphNode> nodeById() {
        return nodes.stream().collect(Collectors.toMap(GraphNode::id, n -> n, (a, b) -> a));
    }

    /** Outgoing edges grouped by source node id. */
    public Map<String, List<GraphEdge>> edgesBySource() {
        return edges.stream().collect(Collectors.groupingBy(GraphEdge::source));
    }

    /** Incoming edges grouped by target node id. */
    public Map<String, List<GraphEdge>> edgesByTarget() {
        return edges.stream().collect(Collectors.groupingBy(GraphEdge::target));
    }
}
