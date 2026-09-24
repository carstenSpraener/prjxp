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
}
