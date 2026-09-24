package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Config-driven {@link ProjectRegistry}: the known projects are those with an embedding store reference.
 * Active when {@code prjxp.hub.enabled} is false or unset (the default).
 * Replaces the per-service resolveProject() copies and the silent "default" fallback:
 * unknown projects fail explicitly with the list of searchable projects.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class StaticProjectRegistry implements ProjectRegistry {
    private final PrjXPConfig cfg;

    /** All project names that have a store reference (i.e. are searchable), deduplicated, config order. */
    @Override
    public List<String> availableProjects() {
        return cfg.getEmbeddingStores().stream()
                .map(PrjXPEmbeddingStoreReference::getProjectName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
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
        if ("default".equalsIgnoreCase(name)) {
            return cfg.getEmbeddingStores().stream().anyMatch(PrjXPEmbeddingStoreReference::isDefault);
        }
        return availableProjects().contains(name);
    }

    /** Throws UnknownProjectException when the resolved project has no searchable store. */
    @Override
    public void ensureSearchable(String requested) {
        String resolved = resolve(requested);
        if (!isSearchable(resolved)) {
            throw new UnknownProjectException(resolved, availableProjects());
        }
    }

    /** "READY" for every searchable project, "UNKNOWN" otherwise. */
    @Override
    public String statusOf(String name) {
        return isSearchable(name) ? "READY" : "UNKNOWN";
    }

    /** All available projects as READY infos (no lifecycle in static mode). */
    @Override
    public List<ProjectInfo> projectInfos() {
        return availableProjects().stream().map(n -> new ProjectInfo(n, "READY", null)).toList();
    }
}
