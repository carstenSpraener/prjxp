package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of {@code semanticGraphOverview}. */
public record SemanticOverviewView(String graphKind, String project, int totalClusters,
                                   List<SemanticClusterSummary> clusters, String model,
                                   String warning, String error) {
    public static SemanticOverviewView unavailable(String message) {
        return new SemanticOverviewView("semantic", null, 0, List.of(), null, null, message);
    }
}
