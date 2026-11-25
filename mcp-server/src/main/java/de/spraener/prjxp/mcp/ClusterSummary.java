package de.spraener.prjxp.mcp;

import java.util.List;

/** One cluster in {@link ClusterOverviewView}: its id, size, and its most connected members. */
public record ClusterSummary(String clusterId, int size, List<String> topMembers) { }
