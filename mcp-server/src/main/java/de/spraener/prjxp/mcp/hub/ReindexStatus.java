package de.spraener.prjxp.mcp.hub;

/**
 * Tracks the progress of an async reindex pipeline for a project.
 */
public enum ReindexStatus {
    IDLE,       // No reindex running
    CHUNKING,   // Running chunk-norris (creating chunks)
    EMBEDDING,  // Computing embeddings (old index still in use)
    IMPORTING,  // Swapping: old index removed, new embeddings imported (briefly non-searchable)
    COMPLETE,   // Reindex finished successfully
    FAILED      // Reindex failed with error
}
