package de.spraener.prjxp.mcp;

import java.util.List;

/** One cluster in the semantic overview. */
public record SemanticClusterSummary(String clusterId, int size, List<String> topFiles) {}
