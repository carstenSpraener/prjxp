package de.spraener.prjxp.mcp;

import java.util.List;

/** Single source of truth for project resolution in the MCP server. */
public interface ProjectRegistry {

    /** All known project names (static: those with a store reference; hub: all registered), sorted. */
    List<String> availableProjects();

    /** null/blank -> active project name (or "default"); "default" passes through; anything else as-is. Never throws. */
    String resolve(String requested);

    /** Whether the project can currently be searched (static: has store ref; hub: status READY). */
    boolean isSearchable(String name);

    /** Throws UnknownProjectException when the resolved project is not searchable. */
    void ensureSearchable(String requested);

    /** "READY" | hub lifecycle status | "UNKNOWN". */
    String statusOf(String name);

    /** All projects with lifecycle info, for the REST /projects endpoints. */
    List<ProjectInfo> projectInfos();
}
