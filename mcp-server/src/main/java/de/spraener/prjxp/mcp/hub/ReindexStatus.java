package de.spraener.prjxp.mcp.hub;

/**
 * Tracks the progress of an async reindex pipeline for a project.
 */
public enum ReindexStatus {
    IDLE,       // No reindex running
    CHUNKING,   // Running chunk-norris (creating chunks)
    EMBEDDING,  // Computing embeddings (old index still in use)
    IMPORTING,  // Swapping: old index removed, new embeddings imported (briefly non-searchable)
    DOCPIPE,    // DocPipe documentation generation (code index untouched, project stays searchable)
    COMPLETE,   // Reindex finished successfully
    FAILED;     // Reindex failed with error

    /**
     * True while a reindex (or DocPipe) run is actively executing and writing to the shared index.
     * Terminal states ({@link #COMPLETE}, {@link #FAILED}) and {@link #IDLE} are NOT active — a
     * finished reindex must not block live updates (the status is not reset to IDLE after completion).
     */
    public boolean isActive() {
        return this == CHUNKING || this == EMBEDDING || this == IMPORTING || this == DOCPIPE;
    }
}
