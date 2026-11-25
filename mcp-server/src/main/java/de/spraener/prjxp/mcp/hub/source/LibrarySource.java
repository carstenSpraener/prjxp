package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Active source: hub-generated library projects under {@code prjxp.hub.libraries-root} (Maven-repo
 * layout). Discovered by the shared recursive marker walk (a library dir carries a generated
 * {@code prjxp.yaml} with the {@code mavenDependency} origin marker). Libraries are SNAPSHOT kind and
 * hub-owned: a vanished library is unregistered only (no index wipe, consistent with snapshot
 * semantics). The recursive walk, outermost-marker-wins, and symlink-cycle guard come from the shared
 * {@link MarkerWalker}.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class LibrarySource implements ProjectSource {

    /** Stable source name — also used by {@code LibraryMaterializer} when stamping a new library. */
    public static final String NAME = "library";

    private final HubProperties hub;
    private final MarkerWalker walker;

    public LibrarySource(HubProperties hub, MarkerWalker walker) {
        this.hub = hub;
        this.walker = walker;
    }

    @Override public String name() { return NAME; }
    @Override public Path root() { return Path.of(hub.getLibrariesRoot()); }
    @Override public ProjectEntry.Kind kind() { return ProjectEntry.Kind.SNAPSHOT; }
    @Override public VanishPolicy vanishPolicy() { return VanishPolicy.UNREGISTER_ONLY; }
    @Override public String markerField() { return "mavenDependency"; }

    @Override
    public Map<String, Path> discover() {
        Map<String, Path> found = walker.walkMarkers(root());
        if (found == null && !rootExists()) {
            // A missing (optional) libraries root yields an empty map, not null: there is simply
            // nothing to sync, and no registered library should be index-wiped just because the
            // root is absent (unregister-only, consistent with snapshot semantics).
            return Map.of();
        }
        return found;   // null = unlistable root (volume glitch) -> skip the sync entirely
    }

    private boolean rootExists() {
        try {
            return Files.isDirectory(root());
        } catch (SecurityException e) {
            return false;
        }
    }
}
