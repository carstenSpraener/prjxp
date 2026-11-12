package de.spraener.prjxp.mcp.hub.dep;

import java.nio.file.Path;

/** Immutable Maven GAV with derived names and repository-relative paths. */
public record MavenCoordinates(String groupId, String artifactId, String version) {

    /** "group:artifact:version" — the index stamp and the yaml origin marker. */
    public String gav() { return groupId + ":" + artifactId + ":" + version; }

    /** Project name: "<artifactId>-<version>" (Maven jar naming). */
    public String name() { return artifactId + "-" + version; }

    /** Repository-relative path: "<groupId-as-path>/<artifactId>/<version>". */
    public Path repoPath() { return Path.of(groupId.replace('.', '/'), artifactId, version); }

    public String pomUrl(String repoBase) {
        return artifactUrl(repoBase, ".pom");
    }
    public String sourcesUrl(String repoBase) {
        return classifierUrl(repoBase, "sources");
    }
    public String javadocUrl(String repoBase) {
        return classifierUrl(repoBase, "javadoc");
    }

    public String classifierUrl(String repoBase, String classifier) {
        return artifactUrl(repoBase, "-" + classifier + ".jar");
    }

    private String artifactUrl(String repoBase, String suffix) {
        return repoBase + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + name() + suffix;
    }
}
