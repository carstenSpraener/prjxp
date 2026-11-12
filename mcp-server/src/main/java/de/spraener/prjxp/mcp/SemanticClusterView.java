package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of {@code semanticGraphCluster}. */
public record SemanticClusterView(String graphKind, String project, String clusterId, int totalMembers,
                                  List<SemanticMember> members, String error) {
    public static SemanticClusterView unavailable(String message) {
        return new SemanticClusterView("semantic", null, null, 0, List.of(), message);
    }
}
