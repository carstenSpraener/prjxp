package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.hub.source.GithubSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Imports a GitHub repository as an independent SNAPSHOT project: shallow-clone into
 * {@code github-root/<name>}, generate its {@code prjxp.yaml} (with the {@code gitUrl} origin marker
 * and a hub-managed JSONL path), register it (source "github"), and enqueue the pipeline.
 * Network I/O (the clone) runs on a DEDICATED single-thread executor — never the request thread or the
 * pipeline worker. {@link #importFromUrlAsync} is non-blocking and never throws.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubImporter {

    private static final Logger log = LoggerFactory.getLogger(GithubImporter.class);

    private final HubProperties props;
    private final GithubGitClient git;
    private final ProjectConfigFileParser configParser;
    private final HubProjectRegistry registry;
    private final PipelineOrchestrator orchestrator;
    private final GithubOperationTracker tracker;
    private final TokenCipher tokenCipher;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prjxp-github");
        t.setDaemon(true);
        return t;
    });

    public GithubImporter(HubProperties props, GithubGitClient git, ProjectConfigFileParser configParser,
                          HubProjectRegistry registry, PipelineOrchestrator orchestrator,
                          GithubOperationTracker tracker, TokenCipher tokenCipher) {
        this.props = props; this.git = git; this.configParser = configParser;
        this.registry = registry; this.orchestrator = orchestrator; this.tracker = tracker;
        this.tokenCipher = tokenCipher;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** Non-blocking: schedule the import on the dedicated executor. Never throws. */
    public void importFromUrlAsync(String url) {
        importFromUrlAsync(url, null);
    }

    /**
     * Non-blocking: schedule the import with an optional PAT on the dedicated executor. Never throws.
     * A {@link TokenCipherException} (e.g. missing key) surfaces as a clear tracker error.
     */
    public void importFromUrlAsync(String url, String token) {
        String key = url.trim();
        tracker.start(key);
        executor.submit(() -> {
            try {
                boolean imported = importFromUrl(url, token);
                tracker.complete(key, true, imported
                        ? "Imported " + deriveName(url)
                        : "Already cloned — skipped");
            } catch (Exception e) {
                log.error("GitHub import of {} failed: {}", url, e.getMessage());
                tracker.complete(key, false, e.getMessage());
            }
        });
    }

    /**
     * Blocking import (also the unit-tested path). Idempotent: an existing populated clone is skipped
     * (returns false); returns true when the clone ran.
     * Throws {@link GithubGitException} on clone failure — the async wrapper catches + logs it.
     */
    public boolean importFromUrl(String url) throws GithubGitException, TokenCipherException {
        return importFromUrl(url, null);
    }

    /**
     * Blocking import with an optional PAT for private repositories. A non-blank {@code token} is
     * passed to git out-of-band via {@code http.extraheader} (never in the URL) and its ENCRYPTED
     * form is written to the generated {@code prjxp.yaml} as {@code gitToken} — the plaintext is
     * never persisted. Throws {@link TokenCipherException} when a token is given but no key is
     * configured (before the clone runs). Throws {@link IllegalStateException} when a project with
     * the derived name already exists in the registry (name collision — the user must delete it
     * first; checked after the idempotency skip, before the clone).
     */
    public boolean importFromUrl(String url, String token) throws GithubGitException, TokenCipherException {
        String name = deriveName(url);
        Path dir = Path.of(props.getGithubRoot(), name);
        if (isPopulated(dir)) {
            log.info("GitHub project {} already cloned at {} — skipping", name, dir);
            return false;
        }
        if (registry.entry(name).isPresent()) {
            // Name collision with an existing project: reject with a clear message instead of
            // silently re-running the old project (the clone dir would be orphaned).
            throw new IllegalStateException("Project '" + name + "' already exists — delete it first");
        }
        String trimmedToken = (token == null || token.isBlank()) ? null : token.trim();
        String encryptedToken = trimmedToken == null ? null : tokenCipher.encrypt(trimmedToken);
        git.clone(url, dir, trimmedToken);
        generateYaml(dir, name, url, encryptedToken);
        if (!registry.entry(name).isPresent()) {
            // SNAPSHOT kind (hub-owned) but source "github" — the source-based vanish filter + the
            // gitUrl marker (not a third Kind) is what identifies it as a GitHub project.
            registry.registerProject(name, dir, ProjectEntry.Kind.SNAPSHOT, GithubSource.NAME);
        } else {
            // Defensive race guard only: the collision check above already rejected any pre-existing
            // entry — this branch only fires if an entry appears between that check and registration.
            registry.reloadDefinition(name);
        }
        orchestrator.enqueueForced(name);
        log.info("Imported GitHub project {} from {} at {}", name, url, dir);
        return true;
    }

    /** True when the clone dir already has a git working tree (idempotency check). */
    public boolean isPopulated(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    /** The bare repository name from a git URL: the last path segment, minus a trailing ".git". */
    public String deriveName(String url) {
        String u = url.trim();
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        int colon = name.lastIndexOf(':');   // scp-like git@host:owner/repo.git
        if (colon >= 0) name = name.substring(colon + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return name.isEmpty() ? "github" : name;
    }

    /**
     * Generate the clone's prjxp.yaml: name, rootDir ".", a HUB-MANAGED absolute JSONL path (never into
     * the git working tree), the gitUrl origin marker, and — for private repos — the ENCRYPTED PAT
     * as {@code gitToken} (never the plaintext).
     */
    private void generateYaml(Path dir, String name, String url, String encryptedToken) {
        try {
            Files.createDirectories(Path.of(props.getGithubJsonlDir()));
        } catch (IOException e) {
            log.warn("Could not create GitHub JSONL dir {}: {}", props.getGithubJsonlDir(), e.toString());
        }
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setRootDir(".");
        def.setJsonlFile(Path.of(props.getGithubJsonlDir(), name + ".jsonl").toString());   // absolute: hub-managed
        def.setGitUrl(url);
        if (encryptedToken != null) {
            def.setGitToken(encryptedToken);
        }
        configParser.save(dir, def);
    }
}
