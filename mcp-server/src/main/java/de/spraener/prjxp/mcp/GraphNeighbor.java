package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;

/**
 * One typed neighbour of a graph node: the edge type, the neighbour's node id and file
 * (null when the neighbour is external and not part of the graph), and whether the edge
 * points outside the project.
 */
public record GraphNeighbor(EdgeType type, String neighborId, String neighborFile, boolean external) {
}
