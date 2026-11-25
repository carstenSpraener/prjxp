package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.ProjectDefinition;

import java.util.List;

/** Single source of truth for project resolution in the MCP server. */
public interface ProjectRegistry {

    /** All known project names (static: those with a store reference; hub: all registered), sorted. */
    List<String> availableProjects();

    /**
     * Resolves a requested project name.
     * <p>
     * A {@code null}/blank request or the legacy sentinel {@code "default"} throws
     * {@link UnknownProjectException} — the project is required, there is no implicit default
     * (the old active-project fallback is obsolete for the multi-project agent-facing surface).
     * Any other name passes through as-is.
     */
    String resolve(String requested);

    /** Whether the project can currently be searched (static: has store ref; hub: status READY). */
    boolean isSearchable(String name);

    /** Throws UnknownProjectException when the resolved project is not searchable. */
    void ensureSearchable(String requested);

    /** "READY" | hub lifecycle status | "UNKNOWN". */
    String statusOf(String name);

    /** All projects with lifecycle info, for the REST /projects endpoints. */
    List<ProjectInfo> projectInfos();

    /**
     * The effective {@link ProjectDefinition} of a project (root dir, JSONL/graph paths),
     * or {@code null} when the project is unknown. Hub implementations resolve this from
     * the marker file; a static registry may return {@code null} for everything.
     */
    ProjectDefinition definitionOf(String name);
}
