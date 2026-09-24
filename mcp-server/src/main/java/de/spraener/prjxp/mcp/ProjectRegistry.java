package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for project resolution in the MCP server.
 * Replaces the per-service resolveProject() copies and the silent "default" fallback:
 * unknown projects fail explicitly with the list of searchable projects.
 */
@Component
@RequiredArgsConstructor
public class ProjectRegistry {
    private final PrjXPConfig cfg;

    /** All project names that have a store reference (i.e. are searchable), deduplicated, config order. */
    public List<String> availableProjects() {
        return cfg.getEmbeddingStores().stream()
                .map(PrjXPEmbeddingStoreReference::getProjectName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
    }

    /** null/blank -> active project name (or "default"); "default" passes through; anything else as-is. Never throws. */
    public String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default");
        }
        if ("default".equalsIgnoreCase(requested)) {
            return "default";
        }
        return requested;
    }

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
    public void ensureSearchable(String requested) {
        String resolved = resolve(requested);
        if (!isSearchable(resolved)) {
            throw new UnknownProjectException(resolved, availableProjects());
        }
    }
}
