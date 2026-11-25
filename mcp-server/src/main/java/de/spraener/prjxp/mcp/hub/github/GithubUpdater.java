package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manually updates a GitHub project: {@code git pull} in the clone, then a forced re-embed. Mirrors the
 * library "reimport" pattern (user-initiated, dedicated executor, non-blocking, never throws). Gated on
 * the {@code gitUrl} origin marker — a non-GitHub project is a no-op.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubUpdater {

    private static final Logger log = LoggerFactory.getLogger(GithubUpdater.class);

    private final HubProjectRegistry registry;
    private final GithubGitClient git;
    private final PipelineOrchestrator orchestrator;
    private final GithubOperationTracker tracker;
    private final GithubTokenResolver tokens;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prjxp-github-update");
        t.setDaemon(true);
        return t;
    });

    public GithubUpdater(HubProjectRegistry registry, GithubGitClient git, PipelineOrchestrator orchestrator,
                         GithubOperationTracker tracker, TokenCipher tokenCipher) {
        this(registry, git, orchestrator, tracker, new GithubTokenResolver(new HubProperties(), tokenCipher));
    }

    @Autowired
    public GithubUpdater(HubProjectRegistry registry, GithubGitClient git, PipelineOrchestrator orchestrator,
                         GithubOperationTracker tracker, GithubTokenResolver tokens) {
        this.registry = registry; this.git = git; this.orchestrator = orchestrator; this.tracker = tracker;
        this.tokens = tokens;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** Non-blocking: schedule the update on the dedicated executor. Never throws. */
    public void updateAsync(String name) {
        tracker.start(name);
        executor.submit(() -> {
            try {
                boolean updated = update(name);
                tracker.complete(name, true, updated
                        ? "Updated " + name
                        : "Skipped — not a GitHub project");
            } catch (Exception e) {
                log.error("GitHub update of '{}' failed: {}", name, e.getMessage());
                tracker.complete(name, false, e.getMessage());
            }
        });
    }

    /**
     * Blocking update (also the unit-tested path). No-op (returns false) when the project is unknown or
     * not a GitHub project (no {@code gitUrl} marker). On success: pull + forced re-embed (returns true).
     * A stored {@code gitToken} is decrypted in memory and passed to git via {@code http.extraheader}
     * (never persisted, never logged). Throws {@link GithubGitException} on pull failure or
     * {@link TokenCipherException} on a missing/wrong key — the async wrapper catches + logs it.
     */
    public boolean update(String name) throws GithubGitException, TokenCipherException {
        ProjectEntry entry = registry.entry(name).orElse(null);
        if (entry == null || entry.getDefinition().getGitUrl() == null) {
            log.info("Skipping GitHub update of '{}' — not a GitHub project", name);
            return false;
        }
        String token = tokens.forProject(entry.getDefinition().getGitToken());
        git.pull(entry.getRootDir(), token);
        orchestrator.enqueueForced(name);
        log.info("Updated GitHub project '{}' (pulled + re-embedded)", name);
        return true;
    }
}
