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
import de.spraener.prjxp.mcp.hub.source.ProjectSource;
import de.spraener.prjxp.mcp.hub.source.VanishPolicy;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final LiveUpdateScanner scanner;             // read-only watermark source for the liveUpdate block

    private final Map<String, ProjectEntry> projects = new ConcurrentHashMap<>();

    /** The project sources this registry discovers (Snapshot, Live, Library; GitHub added in a later phase). */
    private List<ProjectSource> sources = List.of();

    @Autowired(required = false)
    public void setSources(List<ProjectSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Tracks reindex progress per project. */
    private final Map<String, ReindexStatus> reindexStatuses = new ConcurrentHashMap<>();
    /** Percentage progress 0-100 per project. */
    private final Map<String, Integer> reindexProgress = new ConcurrentHashMap<>();

    /**
     * In-memory snapshot of the expensive per-project stats: the Lucene chunk count, the distinct
     * file count, and the live-update watermark (0 for SNAPSHOT projects). The Web UI polls the
     * project list frequently, but computing these per request would scan the index per project
     * (the file count loads every stored field). The cache is refreshed after every pipeline run,
     * after embedding imports, on registration/unregistration, and periodically (safety net for
     * index changes made outside the hub, e.g. a standalone tibed run).
     */
    public record ProjectStats(long chunkCount, int fileCount, long watermark, long computedAt) {
        /** Fallback when a refresh fails: zeroed stats, never null. */
        public static final ProjectStats EMPTY = new ProjectStats(0, 0, 0, 0);
    }

    private final Map<String, ProjectStats> statsCache = new ConcurrentHashMap<>();
    /** Epoch millis of the last periodic stats refresh (0 = not yet). */
    private volatile long lastStatsRefreshAt;

    /** Registers a snapshot project directory (tar import flow). */
    public void registerProject(String name, Path dir) {
        registerProject(name, dir, ProjectEntry.Kind.SNAPSHOT, "snapshot");
    }

    /**
     * Registers a project directory at runtime: parses its config, registers a Lucene DAO and tracks it as IMPORTING.
     * A relative rootDir (including the default ".") is stamped absolute against {@code dir};
     * LIVE projects get their JSONL output redirected to the hub-managed area (never into the live tree).
     */
    public void registerProject(String name, Path dir, ProjectEntry.Kind kind) {
        registerProject(name, dir, kind, sourceNameFor(kind));
    }

    /**
     * Core registration: stamps the entry with the given source name (drives the source-based vanish filter).
     * A relative rootDir (including the default ".") is stamped absolute against {@code dir};
     * LIVE projects get their JSONL output redirected to the hub-managed area (never into the live tree).
     */
    public void registerProject(String name, Path dir, ProjectEntry.Kind kind, String source) {
        ProjectDefinition definition = configParser.parse(dir, name);
        stampRootDir(definition, dir);
        if (kind == ProjectEntry.Kind.LIVE) {
            definition.setJsonlFile(hub.getLiveJsonlDir() + "/" + name + ".jsonl");   // absolute: passes resolvedJsonlFile() through
            ensureLiveJsonlDir();
        }
        PrjXPEmbeddingStoreReference storeRef = new PrjXPEmbeddingStoreReference();
        storeRef.setProjectName(name);
        daoProvider.register(new LucenePxChunkDao(luceneStore, embeddingModel, storeRef));
        projects.put(name, new ProjectEntry(name, dir, kind, source, definition, storeRef));
        refreshStats(name);   // warm the in-memory stats cache (cheap for a fresh/empty index)
        log.info("Registered {} project '{}' (source '{}') at {}", kind, name, source, dir);
    }

    /** Default source for a kind when the caller does not specify one (user-created / tar projects). */
    private static String sourceNameFor(ProjectEntry.Kind kind) {
        return kind == ProjectEntry.Kind.LIVE ? "live" : "snapshot";
    }

    private void stampRootDir(ProjectDefinition def, Path dir) {
        String rootDir = def.getRootDir();
        if (rootDir == null || !isAbsolutePathPortable(rootDir)) {
            def.setRootDir(dir.resolve(rootDir == null ? "." : rootDir).toAbsolutePath().normalize().toString());
        }
    }

    private boolean isAbsolutePathPortable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return true;
        }
        // Accept Unix absolute style independent of host OS (important for cross-platform tests/configs).
        if (value.startsWith("/")) {
            return true;
        }
        // Windows drive absolute path, e.g. C:/x or C:\x
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\');
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
        statsCache.remove(name);   // drop the stale stats snapshot
        log.info("Unregistered project '{}'", name);
    }

    // ------------------------------------------------------------------ In-memory stats cache

    /**
     * Recomputes the expensive per-project stats (Lucene chunk/file counts + live watermark) and
     * atomically replaces the cached snapshot. Never throws: on failure the previous snapshot is
     * kept, so a Web UI request must not fail because of a stats refresh. For an unknown name it
     * drops any stale snapshot (deregistered project).
     */
    public void refreshStats(String name) {
        ProjectEntry entry = projects.get(name);
        if (entry == null) {
            statsCache.remove(name);
            return;
        }
        try {
            long chunkCount = luceneStore.countByProject(name);
            int fileCount = luceneStore.countFilesByProject(name);
            long watermark = entry.getKind() == ProjectEntry.Kind.LIVE ? scanner.watermarkOf(entry) : 0;
            statsCache.put(name, new ProjectStats(chunkCount, fileCount, watermark, System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("Could not refresh stats for project '{}': {} — keeping the previous snapshot", name, e.toString());
        }
    }

    /** Refreshes the stats of all registered projects (periodic safety net). */
    public void refreshStatsAll() {
        for (String name : projects.keySet()) {
            refreshStats(name);
        }
    }

    /**
     * Periodic safety-net refresh of the in-memory stats cache on a fixed 30 s tick (like
     * {@link LiveUpdateScheduler}): the effective interval is
     * {@code prjxp.hub.stats-refresh-interval-ms} (default 60 s). Catches index changes made
     * outside the hub (e.g. a standalone tibed run against the same index). A non-positive
     * interval disables the periodic refresh.
     */
    @Scheduled(fixedDelay = 30_000)
    public void scheduledStatsRefresh() {
        long interval = hub.getStatsRefreshIntervalMs();
        if (interval <= 0) {
            return;   // disabled via configuration
        }
        long now = System.currentTimeMillis();
        if (now - lastStatsRefreshAt < interval) {
            return;   // not yet due
        }
        lastStatsRefreshAt = now;
        refreshStatsAll();
    }

    /** The cached stats snapshot for a project, computing (and caching) it on a cache miss. Never returns null. */
    private ProjectStats statsOfOrCompute(ProjectEntry entry) {
        ProjectStats stats = statsCache.get(entry.getName());
        if (stats == null) {
            refreshStats(entry.getName());
            stats = statsCache.get(entry.getName());
        }
        return stats != null ? stats : ProjectStats.EMPTY;
    }

    /** Updates the lifecycle status of a known project; no-op for unknown names. */
    public void setStatus(String name, ProjectStatus status, String error) {
        ProjectEntry entry = projects.get(name);
        if (entry != null) {
            entry.setStatus(status, error);
        }
    }

    /** Sets the embedding phase of a known project (drives the EMBEDDING_PHASE_n display); no-op for unknown names. */
    public void setEmbeddingPhase(String name, int phase) {
        ProjectEntry entry = projects.get(name);
        if (entry != null) {
            entry.setEmbeddingPhase(phase);
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

    /** Reloads a project's definition from its YAML file on disk. */
    public void reloadDefinition(String name) {
        ProjectEntry entry = projects.get(name);
        if (entry != null) {
            ProjectDefinition freshDef = configParser.parse(entry.getRootDir(), name);
            // Replace the definition in the entry (ProjectEntry uses mutable reference via setter from Lombok @Data)
            // Since ProjectEntry.definition is final, we need to update the fields in place
            ProjectDefinition oldDef = entry.getDefinition();
            oldDef.setName(freshDef.getName());
            oldDef.setRootDir(freshDef.getRootDir());
            oldDef.setJsonlFile(freshDef.getJsonlFile());
            oldDef.setChunoWhiteList(freshDef.getChunoWhiteList());
            oldDef.setExcludeDirNames(freshDef.getExcludeDirNames());
            oldDef.setSourceDirNames(freshDef.getSourceDirNames());
            oldDef.setTibedBatchSize(freshDef.getTibedBatchSize());
            oldDef.setEmbeddingsFile(freshDef.getEmbeddingsFile());
            oldDef.setTibedResetStore(freshDef.isTibedResetStore());
            oldDef.setPrompt(freshDef.getPrompt());
            oldDef.setFulltext(freshDef.isFulltext());
            oldDef.setEmbedDependencies(freshDef.isEmbedDependencies());
            oldDef.setDependencyDepth(freshDef.getDependencyDepth());
            oldDef.setDependencyWhitelist(freshDef.getDependencyWhitelist());
            oldDef.setMavenDependency(freshDef.getMavenDependency());
            oldDef.setGitUrl(freshDef.getGitUrl());
            oldDef.setGitToken(freshDef.getGitToken());
        }
    }

    // ------------------------------------------------------------------ Reindex progress tracking

    public ReindexStatus getReindexStatus(String projectName) {
        return reindexStatuses.getOrDefault(projectName, ReindexStatus.IDLE);
    }

    public int getReindexProgress(String projectName) {
        return reindexProgress.getOrDefault(projectName, 0);
    }

    public void setReindexStatus(String projectName, ReindexStatus status) {
        reindexStatuses.put(projectName, status);
    }

    public void setReindexProgress(String projectName, int progress) {
        reindexProgress.put(projectName, progress);
    }

    public void resetReindexStatus(String projectName) {
        reindexStatuses.remove(projectName);
        reindexProgress.remove(projectName);
    }

    /**
     * Syncs the registry with all configured project sources: registers newly discovered project
     * directories and unregisters vanished ones (per each source's {@link VanishPolicy}). A
     * {@code null} discovery result (volume-glitch) skips that source's sync entirely. Synchronized:
     * startup self-heal and the import poller may call it concurrently.
     */
    public synchronized List<String> discoverProjects() {
        for (ProjectSource source : sources) {
            syncSource(source);
        }
        return availableProjects();
    }

    /**
     * Syncs the registry with one source: registers newly discovered project dirs and unregisters vanished
     * ones (per the source's {@link VanishPolicy}). A {@code null} discovery result (volume-glitch) skips
     * the source's sync entirely. Vanish filtering is source-based: a source only unregisters entries it
     * registered itself (source-name match), so a Library (sharing the SNAPSHOT kind with Snapshot, but a
     * distinct source) is never conflated with a vanished snapshot — and vice versa.
     */
    private void syncSource(ProjectSource source) {
        Map<String, Path> found = source.discover();
        if (found == null) {
            return;   // volume-glitch protection — skip this source's sync entirely
        }
        found.forEach((name, dir) -> {
            ProjectEntry existing = projects.get(name);
            if (existing == null) {
                registerProject(name, dir, source.kind(), source.name());   // stamp the actual source
            } else if (!sameDir(existing.getRootDir(), dir)) {
                log.warn("Project name '{}' from source '{}' collides with an existing entry at {} — skipping {}",
                        name, source.name(), existing.getRootDir(), dir);   // never overwrite a registered project
            }
        });
        projects.values().stream()
                .filter(e -> source.name().equals(e.getSource()))   // source-based (was: kind-based)
                .filter(e -> !found.containsKey(e.getName()))   // marker or directory gone
                .map(ProjectEntry::getName)
                .toList()
                .forEach(name -> {
                    unregisterProject(name);
                    if (source.vanishPolicy() == VanishPolicy.UNREGISTER_AND_WIPE) {
                        luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));   // scoped wipe (shared index!)
                    }
                });
    }

    private static boolean sameDir(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------ ProjectRegistry

    @Override
    public List<String> availableProjects() {
        return projects.keySet().stream().sorted().toList();
    }

    /**
     * null/blank/"default" -> active project (when it is a registered hub project), else the first
     * READY project (sorted), else "default" (so ensureSearchable fails with the project list).
     * Anything else as-is. Never throws.
     * (Known Bug in v0.8.0-rc1, doc/Todos.md §4: the synthetic "default" fallback is not a
     * registered hub project, so an empty projectName always failed in hub mode.)
     */
    @Override
    public String resolve(String requested) {
        if (requested == null || requested.isBlank() || "default".equalsIgnoreCase(requested)) {
            String active = cfg.getActiveProject().map(ProjectDefinition::getName)
                    .filter(projects::containsKey)
                    .orElse(null);
            if (active != null) {
                return active;
            }
            return firstReadyProject().orElse("default");
        }
        return requested;
    }

    /** The first READY project in sorted name order, or empty when none is READY. */
    private Optional<String> firstReadyProject() {
        return projects.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == ProjectStatus.READY)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
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
        if (entry == null) {
            return "UNKNOWN";
        }
        if (entry.getStatus() == ProjectStatus.EMBEDDING && entry.getEmbeddingPhase() > 1) {
            return "EMBEDDING_PHASE_" + entry.getEmbeddingPhase();
        }
        return entry.getStatus().name();
    }

    @Override
    public List<ProjectInfo> projectInfos() {
        return projects.values().stream()
                .sorted(Comparator.comparing(ProjectEntry::getName))
                .map(e -> {
                    // In-memory stats cache — the Web UI polls this endpoint frequently and must
                    // not trigger a per-project index scan on every request.
                    ProjectStats stats = statsOfOrCompute(e);
                    de.spraener.prjxp.common.config.ProjectDefinition def = e.getDefinition();
                    String reindexStatus = getReindexStatus(e.getName()).name();
                    int reindexProgress = getReindexProgress(e.getName());
                    ProjectInfo.LiveUpdateInfo liveUpdate = null;
                    if (e.getKind() == ProjectEntry.Kind.LIVE) {
                        // UPDATING only while the update is actively running on the pipeline worker
                        // (embeddingRunning); a queued-but-not-yet-running update stays IDLE so the
                        // Web UI's indicator marks only the project actually being updated.
                        String st = !def.isLiveUpdate() ? "DISABLED"
                                : e.isEmbeddingRunning() ? "UPDATING" : "IDLE";
                        long wm = stats.watermark();
                        liveUpdate = new ProjectInfo.LiveUpdateInfo(
                                st, wm > 0 ? wm : null, e.getNextScanAt() > 0 ? e.getNextScanAt() : null,
                                e.isEmbeddingRunning() ? e.getLiveUpdateProgress() : null);
                    }
                    return new ProjectInfo(
                        e.getName(),
                        e.getStatus().name(),
                        e.getKind().name(),
                        e.getLastError(),
                        stats.chunkCount(),
                        stats.chunkCount(), // embeddingCount == chunkCount (1:1)
                        stats.fileCount(),
                        def.getRootDir(),
                        def.getJsonlFile(),
                        def.getChunoWhiteList(),
                        def.getTibedBatchSize(),
                        def.getEmbeddingsFile(),
                        def.isTibedResetStore(),
                        reindexStatus,
                        reindexProgress,
                        def.getPrompt(),
                        def.isFulltext(),
                        liveUpdate,
                        def.isEmbedDependencies(),
                        def.getMavenDependency(),
                        def.getGitUrl(),
                        def.getGitUrl() != null && def.getGitToken() != null
                    );
                })
                .toList();
    }
}
