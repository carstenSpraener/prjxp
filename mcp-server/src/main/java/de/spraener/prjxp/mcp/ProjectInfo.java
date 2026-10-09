package de.spraener.prjxp.mcp;

/** REST view of a project: name + lifecycle status + stats + YAML configuration. */
public record ProjectInfo(
    String name,
    String status,       // IMPORTING|CHUNKING|EMBEDDING|READY|FAILED
    String kind,         // LIVE|SNAPSHOT (nullable for backward compat)
    String lastError,
    Long chunkCount,     // from LuceneEmbeddingStore.countByProject()
    Long embeddingCount, // same as chunkCount (1:1 mapping)
    Integer fileCount    // distinct files in project
    // YAML configuration fields (from ProjectDefinition)
    , String rootDir
    , String jsonlFile
    , String chunoWhiteList
    , Integer tibedBatchSize
    , String embeddingsFile
    , Boolean tibedResetStore
    // Reindex progress tracking
    , String reindexStatus   // "IDLE", "CHUNKING", "EMBEDDING", "COMPLETE", "FAILED"
    , Integer reindexProgress // 0-100
    // Standing instructions (from ProjectDefinition)
    , String prompt
) {
    /** Backward-compatible constructor for existing code. */
    public ProjectInfo(String name, String status, String lastError) {
        this(name, status, null, lastError, 0L, 0L, 0, null, null, null, null, null, false, "IDLE", 0, null);
    }

    /** Constructor with stats but no YAML config (uses defaults). */
    public ProjectInfo(String name, String status, String kind, String lastError,
                       Long chunkCount, Long embeddingCount, Integer fileCount) {
        this(name, status, kind, lastError, chunkCount, embeddingCount, fileCount,
              null, null, null, null, null, false, "IDLE", 0, null);
    }
}
