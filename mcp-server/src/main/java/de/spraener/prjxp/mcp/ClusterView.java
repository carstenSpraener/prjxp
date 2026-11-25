package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of the {@code graphCluster} MCP tool. */
public record ClusterView(String project, String clusterId, int totalMembers,
                          List<ClusterMember> members, String error) {
    public static ClusterView unavailable(String message) {
        return new ClusterView(null, null, 0, List.of(), message);
    }
}
