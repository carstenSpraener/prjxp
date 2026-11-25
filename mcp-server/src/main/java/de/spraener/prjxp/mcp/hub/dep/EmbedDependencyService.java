package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives dependency materialization for a project on a DEDICATED single-thread executor —
 * network I/O never runs on the pipeline worker. {@link #syncFor} is non-blocking and never throws.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class EmbedDependencyService {

    private static final Logger log = LoggerFactory.getLogger(EmbedDependencyService.class);

    private final HubProperties props;
    private final HubProjectRegistry registry;
    private final List<DependencyResolver> resolvers;
    private final MavenCentralClient client;
    private final LibraryMaterializer materializer;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prjxp-dep-embed");
        t.setDaemon(true);
        return t;
    });

    public EmbedDependencyService(HubProperties props, HubProjectRegistry registry,
                                  List<DependencyResolver> resolvers,
                                  MavenCentralClient client, LibraryMaterializer materializer) {
        this.props = props; this.registry = registry; this.resolvers = resolvers;
        this.client = client; this.materializer = materializer;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Schedule dependency materialization for the owner. Non-blocking (runs on the dedicated
     * executor). No-op when the owner is unknown or {@code embedDependencies} is false. Never throws.
     */
    public void syncFor(String ownerName) {
        ProjectDefinition def = registry.definitionOf(ownerName);
        if (def == null || !def.isEmbedDependencies()) {
            return;
        }
        executor.submit(() -> {
            try {
                doSync(ownerName, def);
            } catch (Exception e) {
                log.error("Dependency sync for '{}' failed: {}", ownerName, e.getMessage());
            }
        });
    }

    /**
     * Re-materialize a library project (force re-download + re-extract). Gated on the
     * {@code mavenDependency} origin marker. Non-blocking (dedicated executor). Never throws.
     */
    public void reimport(String name) {
        ProjectDefinition def = registry.definitionOf(name);
        if (def == null || def.getMavenDependency() == null) {
            return;   // not a hub-generated library
        }
        executor.submit(() -> {
            try {
                String[] gav = def.getMavenDependency().split(":");
                if (gav.length != 3) {
                    log.warn("Malformed mavenDependency '{}' for '{}' — skipping reimport", def.getMavenDependency(), name);
                    return;
                }
                MavenCoordinates coords = new MavenCoordinates(gav[0], gav[1], gav[2]);
                boolean hasSources = client.exists(coords, "sources");
                boolean hasJavadoc = client.exists(coords, "javadoc");
                ResolvedDependency dep = new ResolvedDependency(coords, 1, hasSources, hasJavadoc);
                materializer.materialize(dep, true);   // force
            } catch (Exception e) {
                log.error("Reimport of library '{}' failed: {}", name, e.getMessage());
            }
        });
    }

    private void doSync(String ownerName, ProjectDefinition def) throws MavenDependencyException {
        Path ownerRoot = Path.of(def.getRootDir()).toAbsolutePath().normalize();
        DependencyResolveConfig config = DependencyResolveConfig.fromDefinition(
                true, def.getDependencyDepth(), def.getDependencyWhitelist());
        for (DependencyResolver resolver : resolvers) {
            if (!resolver.supports(ownerRoot)) {
                continue;
            }
            DependencyResolution resolution = resolver.resolve(ownerRoot, config);
            List<ResolvedDependency> embeddable = resolution.toEmbeddable(client);
            for (ResolvedDependency dep : embeddable) {
                try {
                    materializer.materialize(dep, false);   // idempotent
                } catch (Exception e) {
                    log.warn("Skipping library {} — {}", dep.coords().gav(), e.getMessage());
                    // one bad library must not break the rest
                }
            }
        }
    }
}
