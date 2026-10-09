package de.spraener.prjxp.mcp;

import org.springframework.lang.Nullable;

public record ProjectUpdateRequest(
    @Nullable String rootDir,
    @Nullable String chunoWhiteList,
    @Nullable Integer tibedBatchSize,
    @Nullable String prompt
) {}
