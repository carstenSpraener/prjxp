package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.LucenePxChunkDao;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directory-driven {@link ProjectRegistry} for hub mode: discovers snapshot project directories under the
 * configured projects root and live projects (marker file) anywhere below the import dir, registers a
 * Lucene DAO per project at runtime and tracks lifecycle status. Active only when {@code prjxp.hub.enabled=true}
 * (requires the shared Lucene store).
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HubProjectRegistry implements ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(HubProjectRegistry.class);

    private final PrjXPConfig cfg;
    private final HubProperties hub;
    private final PxChunkDaoProvider daoProvider;
    private final LuceneEmbeddingStore luceneStore;       // shared bean (hub requires store type LUCENE)
    private final EmbeddingModel embeddingModel;          // from prjxp-common EmbeddingModelConfig
    private final ProjectConfigFileParser configParser;

    private final Map<String, ProjectEntry> projects = new ConcurrentHashMap<>();

    /** Registers a snapshot project directory (tar import flow). */
    public void registerProject(String name, Path dir) {
        registerProject(name, dir, ProjectEntry.Kind.SNAPSHOT);
    }

    /**
     * Registers a project directory at runtime: parses its config, registers a Lucene DAO and tracks it as IMPORTING.
     * A relative rootDir (including the default ".") is stamped absolute against {@code dir};
     * LIVE projects get their JSONL output redirected to the hub-managed area (never into the live tree).
     */
    public void registerProject(String name, Path dir, ProjectEntry.Kind kind) {
        ProjectDefinition definition = configParser.parse(dir, name);
        stampRootDir(definition, dir);
        if (kind == ProjectEntry.Kind.LIVE) {
            definition.setJsonlFile(hub.getLiveJsonlDir() + "/" + name + ".jsonl");   // absolute: passes resolvedJsonlFile() through
            ensureLiveJsonlDir();
        }
        PrjXPEmbeddingStoreReference storeRef = new PrjXPEmbeddingStoreReference();
        storeRef.setProjectName(name);
        daoProvider.register(new LucenePxChunkDao(luceneStore, embeddingModel, storeRef));
        projects.put(name, new ProjectEntry(name, dir, kind, definition, storeRef));
    }

    private void stampRootDir(ProjectDefinition def, Path dir) {
        String rootDir = def.getRootDir();
        if (rootDir == null || !Path.of(rootDir).isAbsolute()) {
            def.setRootDir(dir.resolve(rootDir == null ? "." : rootDir).toAbsolutePath().normalize().toString());
        }
    }

    private void ensureLiveJsonlDir() {
        try {
            Files.createDirectories(Path.of(hub.getLiveJsonlDir()));
        } catch (IOException e) {
            log.warn("Could not create live JSONL dir {}: {}", hub.getLiveJsonlDir(), e.toString());
        }
    }

    /** Unregisters a project: removes its DAO(s) from the provider and drops the entry. */
    public void unregisterProject(String name) {
        daoProvider.unregisterByProject(name);
        projects.remove(name);
    }

    /** Updates the lifecycle status of a known project; no-op for unknown names. */
    public void setStatus(String name, ProjectStatus status, String error) {
        ProjectEntry entry = projects.get(name);
        if (entry != null) {
            entry.setStatus(status, error);
        }
    }

    public Optional<ProjectEntry> entry(String name) {
        return Optional.ofNullable(projects.get(name));
    }

    /** The parsed definition of a known project, or null for unknown names. */
    public ProjectDefinition definitionOf(String name) {
        ProjectEntry entry = projects.get(name);
        return entry == null ? null : entry.getDefinition();
    }

    /**
     * Syncs the registry with both roots: registers new snapshot directories (projects root) and new live
     * projects (import dir with a marker file), unregisters vanished ones. A disappeared LIVE project is
     * additionally wiped from the shared index; a missing/unlistable import dir skips live sync entirely
     * (volume-glitch protection). Synchronized: startup self-heal and the import poller may call it concurrently.
     */
    public synchronized List<String> discoverProjects() {
        syncSnapshots();
        syncLive();
        return availableProjects();
    }

    private void syncSnapshots() {
        Map<String, Path> found = listProjectDirs();   // empty when the root is missing
        found.forEach((name, dir) -> {
            if (!projects.containsKey(name)) {
                registerProject(name, dir);   // SNAPSHOT
            }
        });
        projects.values().stream()
                .filter(e -> e.getKind() == ProjectEntry.Kind.SNAPSHOT)
                .filter(e -> !found.containsKey(e.getName()))
                .map(ProjectEntry::getName)
                .toList()
                .forEach(this::unregisterProject);   // unchanged: unregister only, no index wipe
    }

    private void syncLive() {
        Map<String, Path> found = listLiveDirs();
        if (found == null) {
            return;   // import dir missing/unlistable — skip live sync entirely (volume-glitch protection)
        }
        found.forEach((name, dir) -> {
            ProjectEntry existing = projects.get(name);
            if (existing == null) {
                registerProject(name, dir, ProjectEntry.Kind.LIVE);   // status IMPORTING — the poller enqueues it
            } else if (!sameDir(existing.getRootDir(), dir)) {
                log.warn("Live project name '{}' collides with an existing entry at {} — skipping {}",
                        name, existing.getRootDir(), dir);   // never overwrite a registered project
            }
        });
        projects.values().stream()
                .filter(e -> e.getKind() == ProjectEntry.Kind.LIVE)
                .filter(e -> !found.containsKey(e.getName()))   // marker or directory gone
                .map(ProjectEntry::getName)
                .toList()
                .forEach(name -> {
                    unregisterProject(name);
                    luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));   // scoped wipe (shared index!)
                });
    }

    private static boolean sameDir(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private Map<String, Path> listProjectDirs() {
        Map<String, Path> found = new HashMap<>();
        Path root = Path.of(hub.getProjectsRoot());
        if (!Files.isDirectory(root)) {
            return found;   // missing dir -> empty
        }
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> found.put(dir.getFileName().toString(), dir));
        } catch (IOException e) {
            log.warn("Could not list hub projects root {}: {}", root, e.toString());
        }
        return found;
    }

    /**
     * Directories with a prjxp.yaml/yml marker anywhere below the import dir (recursive walk), keyed by
     * project name (yaml {@code name} or directory name). The outermost marker wins: the walk stops
     * descending into a project tree, so nested markers belong to that project. Returns null when the
     * import dir is missing or any part of it is unlistable (volume-glitch protection — callers must
     * skip live sync, not wipe everything).
     */
    private Map<String, Path> listLiveDirs() {
        Path root = Path.of(hub.getImportDir());
        if (!Files.isDirectory(root)) {
            return null;   // missing -> skip live sync entirely
        }
        Map<String, Path> found = new HashMap<>();
        Set<Path> visitedReal = new HashSet<>();   // symlink-cycle / duplicate-link protection
        try (var dirs = Files.list(root)) {
            List<Path> subDirs = dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))   // deterministic on name collisions
                    .toList();
            for (Path dir : subDirs) {
                collectLiveDir(dir, visitedReal, found);
            }
        } catch (IOException e) {
            log.warn("Could not list hub import dir {}: {}", root, e.toString());
            return null;   // unlistable (anywhere in the tree) -> skip live sync entirely (volume-glitch protection)
        }
        return found;
    }

    /**
     * Recursively collects live projects below {@code dir}: a directory with a marker file becomes a
     * project and the walk stops there (outermost marker wins). Children are visited in sorted order
     * for deterministic name-collision handling.
     */
    private void collectLiveDir(Path dir, Set<Path> visitedReal, Map<String, Path> found) throws IOException {
        if (configParser.markerFile(dir).isPresent()) {
            registerLiveDir(dir, found);
            return;   // never descend into a project tree — nested markers belong to it
        }
        if (!visitedReal.add(dir.toRealPath())) {
            return;   // already visited (symlink cycle or duplicate link) — prune
        }
        try (var children = Files.list(dir)) {
            List<Path> subDirs = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path sub : subDirs) {
                collectLiveDir(sub, visitedReal, found);
            }
        }
    }

    private void registerLiveDir(Path dir, Map<String, Path> found) {
        String name = configParser.parse(dir, dir.getFileName().toString()).getName();   // yaml name or directory name
        if (found.containsKey(name)) {
            log.warn("Duplicate live project name '{}' in import dir — keeping {}", name, found.get(name));
        } else {
            found.put(name, dir);
        }
    }

    // ------------------------------------------------------------------ ProjectRegistry

    @Override
    public List<String> availableProjects() {
        return projects.keySet().stream().sorted().toList();
    }

    /** null/blank -> active project name (or "default"); "default" passes through; anything else as-is. Never throws. */
    @Override
    public String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default");
        }
        if ("default".equalsIgnoreCase(requested)) {
            return "default";
        }
        return requested;
    }

    @Override
    public boolean isSearchable(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        ProjectEntry entry = projects.get(name);
        return entry != null && entry.getStatus() == ProjectStatus.READY;
    }

    /** Throws UnknownProjectException when the resolved project is not searchable. */
    @Override
    public void ensureSearchable(String requested) {
        String resolved = resolve(requested);
        if (!isSearchable(resolved)) {
            throw new UnknownProjectException(resolved, availableProjects());
        }
    }

    @Override
    public String statusOf(String name) {
        if (name == null || name.isBlank()) {
            return "UNKNOWN";
        }
        ProjectEntry entry = projects.get(name);
        return entry == null ? "UNKNOWN" : entry.getStatus().name();
    }

    @Override
    public List<ProjectInfo> projectInfos() {
        return projects.values().stream()
                .sorted(Comparator.comparing(ProjectEntry::getName))
                .map(e -> new ProjectInfo(e.getName(), e.getStatus().name(), e.getLastError()))
                .toList();
    }
}
