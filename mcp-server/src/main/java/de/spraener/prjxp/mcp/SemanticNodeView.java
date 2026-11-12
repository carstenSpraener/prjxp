package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of {@code semanticGraphNode}. */
public record SemanticNodeView(String graphKind, String project, String nodeId, String file, String mime,
                               String clusterId, List<SemanticNeighbor> neighbors, String error) {
    public static SemanticNodeView unavailable(String message) {
        return new SemanticNodeView("semantic", null, null, null, null, null, List.of(), message);
    }
}
