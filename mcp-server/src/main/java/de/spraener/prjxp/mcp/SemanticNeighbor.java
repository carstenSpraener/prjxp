package de.spraener.prjxp.mcp;

/** One project-scoped kNN neighbour in the semantic graph. */
public record SemanticNeighbor(String id, String file, String mime, double score) {}
