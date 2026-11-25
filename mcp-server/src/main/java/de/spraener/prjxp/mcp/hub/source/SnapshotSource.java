package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * Passive source: snapshot projects are any immediate subdirectory of {@code prjxp.hub.projects-root}
 * (created by the tar / embedded import flow). One-level discovery, no marker required, keyed by dir
 * name. Vanished snapshots are unregistered only (no index wipe). A missing root yields an empty map.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class SnapshotSource implements ProjectSource {

    private final HubProperties hub;
    private final MarkerWalker walker;

    public SnapshotSource(HubProperties hub, MarkerWalker walker) {
        this.hub = hub;
        this.walker = walker;
    }

    @Override public String name() { return "snapshot"; }
    @Override public Path root() { return Path.of(hub.getProjectsRoot()); }
    @Override public ProjectEntry.Kind kind() { return ProjectEntry.Kind.SNAPSHOT; }
    @Override public VanishPolicy vanishPolicy() { return VanishPolicy.UNREGISTER_ONLY; }
    @Override public String markerField() { return null; }

    @Override
    public Map<String, Path> discover() {
        return walker.listSubdirs(root());   // empty map when the root is missing
    }
}
