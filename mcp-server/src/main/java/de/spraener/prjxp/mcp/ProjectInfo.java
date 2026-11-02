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
    // Lossless fulltext copy switch (from ProjectDefinition)
    , Boolean fulltext
    // Live-update freshness block (null for SNAPSHOT projects)
    , LiveUpdateInfo liveUpdate
) {
    /**
     * Live-update freshness block for LIVE projects: {@code status} is {@code IDLE} (up to date,
     * including a live update that is queued but not yet running), {@code UPDATING} (a live update
     * is actively running on the pipeline worker) or {@code DISABLED} (per-project opt-out);
     * {@code lastGeneratedAt} is the {@code generatedAt} watermark of the last run (null when no run yet);
     * {@code nextScanAt} is the next scheduled scan (null when not yet scheduled);
     * {@code progress} is the 0-100 progress of the running update (null when not {@code UPDATING}).
     */
    public record LiveUpdateInfo(String status, Long lastGeneratedAt, Long nextScanAt, Integer progress) {}

    /** Backward-compatible constructor for existing code. */
    public ProjectInfo(String name, String status, String lastError) {
        this(name, status, null, lastError, 0L, 0L, 0, null, null, null, null, null, false, "IDLE", 0, null, null, null);
    }

    /** Constructor with stats but no YAML config (uses defaults). */
    public ProjectInfo(String name, String status, String kind, String lastError,
                       Long chunkCount, Long embeddingCount, Integer fileCount) {
        this(name, status, kind, lastError, chunkCount, embeddingCount, fileCount,
              null, null, null, null, null, false, "IDLE", 0, null, null, null);
    }
}
