package de.spraener.prjxp.mcp;

/** One member of a semantic cluster. id is the unique semantic node id. */
public record SemanticMember(String id, String file, String mime, String preview) {}
