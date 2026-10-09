package de.spraener.prjxp.mcp;

public record QueryStatEntry(
    String query,
    Long responseTimeMs,
    Integer resultCount,
    Boolean fromCache
) {}
