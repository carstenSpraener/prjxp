package de.spraener.prjxp.mcp.hub;

import java.nio.file.Path;

/** Callback for finished import extractions. Implemented by the pipeline orchestrator (Phase 04). */
public interface ImportHandler {
    /** Extraction succeeded; projectDir is the extracted project root. */
    void onImported(String name, Path projectDir);
    /** Extraction failed; the tar was renamed to <name>.tar.failed by the poller. */
    void onFailed(String name, String error);
}
