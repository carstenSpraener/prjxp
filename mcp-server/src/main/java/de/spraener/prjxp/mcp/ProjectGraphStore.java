package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.graph.CodeGraphIo;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches the code knowledge graph ({@code graph.json}) of a project.
 *
 * <p>The cache is keyed by the resolved project name and invalidated on mtime/size
 * change of the graph file, so a re-indexed project is picked up without a restart.
 * Encrypted graph files are transparently decrypted with the transfer password
 * (missing password or a corrupt file degrade to {@link Optional#empty()}, never a throw).
 *
 * <p>Hub-only: the graph tools are part of the hub web stack, and the {@link ProjectRegistry}
 * dependency does not exist in CLI mode.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log
public class ProjectGraphStore {

    private final PrjXPConfig cfg;
    private final TransferPasswordResolver transferPasswordResolver;
    private final ProjectRegistry projectRegistry;

    /** Not a Spring bean — instantiated here (stateless Jackson (de)serializer). */
    private final CodeGraphIo graphIo = new CodeGraphIo();

    private final Map<String, CachedGraph> cache = new ConcurrentHashMap<>();

    private record CachedGraph(long mtimeMillis, long size, CodeGraph graph) {
    }

    /**
     * Returns the project's code graph, or empty when the project has no graph file,
     * the file is missing (and nothing is cached), or it cannot be decoded.
     */
    public Optional<CodeGraph> graphFor(String project) {
        String resolved = projectRegistry.resolve(project);
        // Hub projects are registered from their prjxp.yaml marker and are NOT part of
        // PrjXPConfig.projects; static/CLI setups only have the config list. Check both.
        ProjectDefinition pd = cfg.getProjectDefinition(resolved).orElse(null);
        if (pd == null) {
            pd = projectRegistry.definitionOf(resolved);
        }
        String graphFile = pd == null ? null : pd.resolvedGraphFile();
        if (graphFile == null || graphFile.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(graphFile);
        if (!Files.isRegularFile(path)) {
            // A vanished graph file means "not available, please reindex" — never serve stale data.
            cache.remove(resolved);
            return Optional.empty();
        }
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            CachedGraph cached = cache.get(resolved);
            if (cached != null && cached.mtimeMillis() == mtime && cached.size() == size) {
                return Optional.of(cached.graph());
            }
            CodeGraph graph = load(path);
            if (graph == null) {
                return Optional.empty();
            }
            cache.put(resolved, new CachedGraph(mtime, size, graph));
            return Optional.of(graph);
        } catch (IOException | RuntimeException e) {
            log.warning("Could not read graph file '" + graphFile + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads and decodes the graph file; returns null when it cannot be decoded. */
    private CodeGraph load(Path path) {
        char[] password = password();
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = TransferCrypto.openAuto(raw, password)) {
            return graphIo.read(in);
        } catch (IOException | RuntimeException e) {
            log.warning("Could not decode graph file '" + path + "': " + e.getMessage());
            return null;
        }
    }

    private char[] password() {
        String envKey = cfg.getTransfer() != null ? cfg.getTransfer().getPasswordEnv() : null;
        if (envKey == null || envKey.isBlank()) {
            envKey = TransferPasswordResolver.DEFAULT_ENV_KEY;
        }
        return transferPasswordResolver.resolvePassword(envKey);
    }
}
