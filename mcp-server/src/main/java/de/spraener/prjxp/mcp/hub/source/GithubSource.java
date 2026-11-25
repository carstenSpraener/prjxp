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
 * Active source: hub-cloned GitHub projects under {@code prjxp.hub.github-root}. Discovered by the
 * shared recursive marker walk (a clone carries a generated {@code prjxp.yaml} with the {@code gitUrl}
 * origin marker). GitHub projects are SNAPSHOT kind (hub-owned) but mutable: a genuinely vanished clone
 * (root still listable) is unregistered AND wiped from the shared index. Discovery is null-safe like
 * {@link LiveSource}: a missing/unlistable root skips the sync entirely (a volume glitch never wipes).
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubSource implements ProjectSource {

    /** Stable source name — also used by {@code GithubImporter} when stamping a new clone. */
    public static final String NAME = "github";

    private static final Logger log = LoggerFactory.getLogger(GithubSource.class);

    private final HubProperties hub;
    private final MarkerWalker walker;
    /** Throttles the "github root missing" warning to once per outage (mirrors LiveSource). */
    private final AtomicBoolean rootMissingWarned = new AtomicBoolean(false);

    public GithubSource(HubProperties hub, MarkerWalker walker) {
        this.hub = hub;
        this.walker = walker;
    }

    @Override public String name() { return NAME; }
    @Override public Path root() { return Path.of(hub.getGithubRoot()); }
    @Override public ProjectEntry.Kind kind() { return ProjectEntry.Kind.SNAPSHOT; }
    @Override public VanishPolicy vanishPolicy() { return VanishPolicy.UNREGISTER_AND_WIPE; }
    @Override public String markerField() { return "gitUrl"; }

    @Override
    public Map<String, Path> discover() {
        Map<String, Path> found = walker.walkMarkers(root());
        if (found == null) {
            if (rootMissingWarned.compareAndSet(false, true)) {
                log.warn("Hub github root {} does not exist (yet) — is the volume mounted? "
                        + "GitHub project scan is skipped until it appears (retried every poll, this warning logs only once).", root());
            }
            return null;   // volume-glitch: skip the sync entirely (NEVER wipe)
        }
        if (rootMissingWarned.compareAndSet(true, false)) {
            log.info("Hub github root {} is now available — resuming github project scan", root());
        }
        return found;
    }
}
