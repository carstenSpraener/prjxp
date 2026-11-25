package de.spraener.prjxp.mcp.hub.dep;

import java.nio.file.Path;

/** Resolves a project's build-file dependencies into a {@link DependencyResolution}. */
public interface DependencyResolver {
    /** True when this resolver can handle the project's build file (pom.xml / build.gradle). */
    boolean supports(Path projectRoot);
    /** Resolve the dependency tree (bounded). Never throws on a missing build file (returns empty). */
    DependencyResolution resolve(Path projectRoot, DependencyResolveConfig config) throws MavenDependencyException;
}
