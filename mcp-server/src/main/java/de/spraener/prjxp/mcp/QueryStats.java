package de.spraener.prjxp.mcp;

import java.util.List;

public record QueryStats(
    Long totalQueries,
    Double avgResponseTimeMs,
    Double p95ResponseTimeMs,
    Long cacheHits,
    Long cacheMisses,
    List<QueryStatEntry> recentQueries
) {}
