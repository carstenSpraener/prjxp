package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.ProjectEntry;

import java.nio.file.Path;
import java.util.Map;

/**
 * A project source: a root directory the hub discovers projects in, plus the lifecycle policy for the
 * projects it produces. The registry loops over all sources in {@code discoverProjects()}.
 *
 * <p>Passive sources (Snapshot, Live) are only discovered. Active sources (Library, GitHub) keep their
 * materialize/update logic in dedicated service classes ({@code LibraryMaterializer},
 * {@code GithubImporter}/{@code GithubUpdater}) — NOT on this interface. This interface is discovery +
 * lifecycle policy only, so the registry's {@code discoverProjects()} loop stays source-agnostic.
 */
public interface ProjectSource {

    /** Stable source name: "snapshot" | "live" | "library" | "github". */
    String name();

    /** The root directory this source discovers projects in. */
    Path root();

    /** The kind of projects this source produces. */
    ProjectEntry.Kind kind();

    /** What to do when a registered project of this source vanishes. */
    VanishPolicy vanishPolicy();

    /** The prjxp.yaml origin-marker field this source stamps (null for Snapshot / Live). */
    String markerField();

    /**
     * Discover project directories under {@link #root()}. Returns project name to directory.
     * Contract: {@code null} = root missing/unlistable, skip this source's sync entirely (volume-glitch);
     * empty map = root exists but holds no projects (unregister all vanished).
     */
    Map<String, Path> discover();
}
