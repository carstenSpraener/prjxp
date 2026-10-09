package de.spraener.prjxp.mcp;

import java.util.List;

public record ProjectStats(
    String name,
    Long chunkCount,
    Long embeddingCount,
    Integer fileCount,
    String rootDir,
    List<String> languages
) {}
