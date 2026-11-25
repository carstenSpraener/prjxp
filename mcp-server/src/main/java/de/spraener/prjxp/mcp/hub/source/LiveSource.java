package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passive source: live projects are directories with a prjxp.yaml/yml marker anywhere below
 * {@code prjxp.hub.import-dir} (user-owned trees). Recursive discovery with volume-glitch protection:
 * a missing/unlistable import dir skips the sync entirely (never wipes). Vanished live projects are
 * unregistered AND wiped from the shared index.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class LiveSource implements ProjectSource {

    private static final Logger log = LoggerFactory.getLogger(LiveSource.class);

    private final HubProperties hub;
    private final MarkerWalker walker;
    /** Throttles the "import dir missing" warning to once per outage (moved from HubProjectRegistry). */
    private final AtomicBoolean importDirMissingWarned = new AtomicBoolean(false);

    public LiveSource(HubProperties hub, MarkerWalker walker) {
        this.hub = hub;
        this.walker = walker;
    }

    @Override public String name() { return "live"; }
    @Override public Path root() { return Path.of(hub.getImportDir()); }
    @Override public ProjectEntry.Kind kind() { return ProjectEntry.Kind.LIVE; }
    @Override public VanishPolicy vanishPolicy() { return VanishPolicy.UNREGISTER_AND_WIPE; }
    @Override public String markerField() { return null; }

    @Override
    public Map<String, Path> discover() {
        Map<String, Path> found = walker.walkMarkers(root());
        if (found == null) {
            if (importDirMissingWarned.compareAndSet(false, true)) {
                log.warn("Hub import dir {} does not exist (yet) — is the volume mounted? "
                        + "Live project scan is skipped until it appears (retried every poll, this warning logs only once).", root());
            }
            return null;   // volume-glitch: skip the sync entirely
        }
        if (importDirMissingWarned.compareAndSet(true, false)) {
            log.info("Hub import dir {} is now available — resuming live project scan", root());
        }
        return found;
    }
}
