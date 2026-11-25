package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of the {@code graphClusterOverview} MCP tool. */
public record ClusterOverviewView(String project, int totalClusters,
                                  List<ClusterSummary> clusters, String error) {
    public static ClusterOverviewView unavailable(String message) {
        return new ClusterOverviewView(null, 0, List.of(), message);
    }
}
