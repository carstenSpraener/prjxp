package de.spraener.prjxp.mcp;

/** One member of a cluster in {@link ClusterView}. */
public record ClusterMember(String id, String file, double weight, int inDegree, int outDegree) { }
