package de.spraener.prjxp.mcp.hub.dep;

/** A resolved dependency at a given hop depth (1 = direct). */
public record RawDependency(MavenCoordinates coords, int depth) {}
