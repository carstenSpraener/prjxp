package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.springframework.stereotype.Component;

/**
 * Thread-local context holder to pass the active ProjectDefinition through the veto chain.
 * Used by VetoRegistry to set context before evaluating vetos, and by veto implementations
 * (e.g. StandardVetos) to read the project-specific configuration instead of falling back
 * to the global PrjXPConfig active project.
 */
@Component
public class VetoContext {

    private final ThreadLocal<ProjectDefinition> definition = new ThreadLocal<>();

    public void set(ProjectDefinition def) {
        definition.set(def);
    }

    public ProjectDefinition get() {
        return definition.get();
    }

    public void clear() {
        definition.remove();
    }
}
