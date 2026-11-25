package de.spraener.prjxp.mcp.hub.source;

/** What the registry does when a registered project's directory vanishes during a source sync. */
public enum VanishPolicy {
    /** Drop the registry entry only; the shared index is left intact (Snapshot / Library semantics). */
    UNREGISTER_ONLY,
    /** Drop the registry entry AND wipe the project's chunks from the shared index (Live semantics). */
    UNREGISTER_AND_WIPE
}
