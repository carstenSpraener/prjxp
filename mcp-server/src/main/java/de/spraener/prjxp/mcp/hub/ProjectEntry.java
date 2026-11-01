package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;

import java.nio.file.Path;
import lombok.Getter;

@Getter
public class ProjectEntry {

    /** SNAPSHOT: extracted from a tar into the projects root (hub owns the tree). LIVE: registered in place from the import dir (user owns the tree). */
    public enum Kind { SNAPSHOT, LIVE }

    private final String name;
    private final Path rootDir;
    private final Kind kind;
    private final ProjectDefinition definition;
    private final PrjXPEmbeddingStoreReference storeRef;

    @Getter(lombok.AccessLevel.NONE)
    private volatile ProjectStatus status = ProjectStatus.IMPORTING;

    @Getter(lombok.AccessLevel.NONE)
    private volatile String lastError;

    /** 1-based depth of the embedding run within the current run chain (drives the EMBEDDING_PHASE_n display). */
    @Getter(lombok.AccessLevel.NONE)
    private volatile int embeddingPhase = 1;

    /** Next scheduled live-update scan (epoch millis; 0 = not yet scheduled). */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long nextScanAt;

    /** Adaptive polling interval for live updates (base → cap). */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long currentIntervalMs;

    /** Set on live-update completion, consumed by the scheduler. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile boolean rescanRequested;

    /** A live update is queued/running. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile boolean embeddingInFlight;

    public ProjectEntry(String name, Path rootDir, Kind kind, ProjectDefinition definition, PrjXPEmbeddingStoreReference storeRef) {
        this.name = name;
        this.rootDir = rootDir;
        this.kind = kind;
        this.definition = definition;
        this.storeRef = storeRef;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized void setStatus(ProjectStatus newStatus, String error) {
        this.status = newStatus;
        this.lastError = error;
    }

    public int getEmbeddingPhase() { return embeddingPhase; }
    public synchronized void setEmbeddingPhase(int phase) { this.embeddingPhase = Math.max(1, phase); }

    public long getNextScanAt() { return nextScanAt; }
    public void setNextScanAt(long t) { this.nextScanAt = t; }
    public long getCurrentIntervalMs() { return currentIntervalMs; }
    public void setCurrentIntervalMs(long ms) { this.currentIntervalMs = ms; }
    public boolean isRescanRequested() { return rescanRequested; }
    public void setRescanRequested(boolean b) { this.rescanRequested = b; }
    public boolean isEmbeddingInFlight() { return embeddingInFlight; }
    public void setEmbeddingInFlight(boolean b) { this.embeddingInFlight = b; }
}
