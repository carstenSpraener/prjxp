package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.LucenePxChunkDao;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directory-driven {@link ProjectRegistry} for hub mode: discovers project directories under the
 * configured projects root, registers a Lucene DAO per project at runtime and tracks lifecycle status.
 * Active only when {@code prjxp.hub.enabled=true} (requires the shared Lucene store).
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

    /** Registers a project directory at runtime: parses its config, registers a Lucene DAO and tracks it as IMPORTING. */
    public void registerProject(String name, Path dir) {
        ProjectDefinition definition = configParser.parse(dir, name);
        PrjXPEmbeddingStoreReference storeRef = new PrjXPEmbeddingStoreReference();
        storeRef.setProjectName(name);
        daoProvider.register(new LucenePxChunkDao(luceneStore, embeddingModel, storeRef));
        projects.put(name, new ProjectEntry(name, dir, definition, storeRef));
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
     * Syncs the registry with the projects root: registers new directories, unregisters vanished ones.
     * A missing (or unreadable) root yields an empty discovery, i.e. all entries are unregistered.
     */
    public List<String> discoverProjects() {
        Map<String, Path> found = listProjectDirs();
        found.forEach((name, dir) -> {
            if (!projects.containsKey(name)) {
                registerProject(name, dir);
            }
        });
        projects.keySet().stream()
                .filter(name -> !found.containsKey(name))
                .toList()
                .forEach(this::unregisterProject);
        return availableProjects();
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
