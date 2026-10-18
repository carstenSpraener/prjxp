package de.spraener.prjxp.mcp;

import org.springframework.lang.Nullable;

public record ProjectCreateRequest(
    String name,
    String kind,            // "LIVE" or "SNAPSHOT"
    @Nullable String rootDir,
    @Nullable String chunoWhiteList,
    @Nullable Integer tibedBatchSize,
    @Nullable Boolean fulltext
) {}
