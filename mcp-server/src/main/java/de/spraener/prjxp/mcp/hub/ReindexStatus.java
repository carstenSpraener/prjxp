package de.spraener.prjxp.mcp.hub;

/**
 * Tracks the progress of an async reindex pipeline for a project.
 */
public enum ReindexStatus {
    IDLE,      // No reindex running
    CHUNKING,  // Running chunk-norris (creating chunks)
    EMBEDDING, // Running tibed (creating embeddings)
    COMPLETE,   // Reindex finished successfully
    FAILED      // Reindex failed with error
}
