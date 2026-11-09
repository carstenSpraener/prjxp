package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
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
import java.util.Set;

/**
 * Shared directory-walk helper for project discovery. Two modes:
 * <ul>
 *   <li>{@link #listSubdirs} — one level, every subdirectory is a project, keyed by directory name
 *       (Snapshot). Returns an empty map when the root is missing.</li>
 *   <li>{@link #walkMarkers} — recursive, only directories with a prjxp.yaml/yml marker are projects,
 *       keyed by yaml name (or directory name), with exclude-dir pruning, max-depth bound,
 *       outermost-marker-wins, and a symlink-cycle guard (Live; reused by Library and GitHub).
 *       Returns {@code null} when the root is missing/unlistable (volume-glitch protection).</li>
 * </ul>
 * Stateless: the per-root "missing dir" warning is owned by the source, not the walker.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class MarkerWalker {

    private static final Logger log = LoggerFactory.getLogger(MarkerWalker.class);

    private final HubProperties hub;
    private final ProjectConfigFileParser configParser;

    public MarkerWalker(HubProperties hub, ProjectConfigFileParser configParser) {
        this.hub = hub;
        this.configParser = configParser;
    }

    /** One-level listing: every immediate subdirectory of {@code root} is a project, keyed by its bare name. */
    public Map<String, Path> listSubdirs(Path root) {
        Map<String, Path> found = new HashMap<>();
        if (!Files.isDirectory(root)) {
            return found;   // missing root -> empty (caller unregisters vanished)
        }
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> found.put(dir.getFileName().toString(), dir));
        } catch (IOException e) {
            log.warn("Could not list projects root {}: {}", root, e.toString());
        }
        return found;
    }

    /**
     * Recursive marker walk. Returns {@code null} when {@code root} is missing or unlistable
     * (volume-glitch protection — the caller must skip the sync, not wipe). Returns an empty map when
     * the root exists but holds no markers.
     */
    public Map<String, Path> walkMarkers(Path root) {
        if (!Files.isDirectory(root)) {
            return null;   // missing -> skip sync entirely
        }
        Set<String> excludeDirs = hub.getScanExcludeDirNames();
        int maxDepth = hub.getScanMaxDepth();
        Map<String, Path> found = new HashMap<>();
        Set<Path> visitedReal = new HashSet<>();   // symlink-cycle / duplicate-link protection
        List<Path> subDirs;
        try (var dirs = Files.list(root)) {
            subDirs = dirs.filter(Files::isDirectory)
                    .filter(p -> !isExcluded(p, excludeDirs))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))   // deterministic on name collisions
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list scan root {}: {}", root, e.toString());
            return null;   // unlistable root -> skip sync entirely
        }
        if (configParser.isExcluded(root)) {
            return found;   // .prjxp-exclude at the root — nothing to scan (empty, not null: the dir exists)
        }
        if (configParser.markerFile(root).isPresent()) {
            registerMarkerDir(root, found);
            return found;   // outermost marker wins — never descend into a project tree
        }
        for (Path dir : subDirs) {
            collectMarkerDirSafely(dir, 1, excludeDirs, maxDepth, visitedReal, found);
        }
        return found;
    }

    /**
     * Collects projects below {@code dir}, isolating unreadable subtrees: an {@link IOException}
     * anywhere below one top-level (or nested) entry only skips that entry, so a single bad directory
     * (e.g. an unreadable folder, a broken symlink, or a path exceeding the host filesystem's limits)
     * never aborts the scan of unrelated sibling projects.
     */
    private void collectMarkerDirSafely(Path dir, int depth, Set<String> excludeDirs, int maxDepth,
                                        Set<Path> visitedReal, Map<String, Path> found) {
        try {
            collectMarkerDir(dir, depth, excludeDirs, maxDepth, visitedReal, found);
        } catch (IOException e) {
            log.warn("Skipping unreadable directory {} while scanning for projects: {}", dir, e.toString());
        }
    }

    /**
     * Recursively collects projects below {@code dir}: a directory with a marker file becomes a
     * project and the walk stops there (outermost marker wins). Children are visited in sorted order
     * for deterministic name-collision handling. A {@value ProjectConfigFileParser#EXCLUDE_MARKER_FILE_NAME}
     * file makes the hub ignore {@code dir} entirely — it is neither registered nor descended into, even
     * if a {@code prjxp.yaml}/{@code .yml} also sits there. Recursion stops once {@code depth} reaches
     * {@code prjxp.hub.scan-max-depth} — bounds the cost of huge marker-less sibling trees.
     */
    private void collectMarkerDir(Path dir, int depth, Set<String> excludeDirs, int maxDepth,
                                  Set<Path> visitedReal, Map<String, Path> found) throws IOException {
        if (configParser.isExcluded(dir)) {
            return;   // .prjxp-exclude marker — directory (and its whole subtree) is ignored
        }
        if (configParser.markerFile(dir).isPresent()) {
            registerMarkerDir(dir, found);
            return;   // never descend into a project tree — nested markers belong to it
        }
        if (depth >= maxDepth) {
            return;   // bounds runaway recursion into huge, marker-less subtrees
        }
        if (!visitedReal.add(dir.toRealPath())) {
            return;   // already visited (symlink cycle or duplicate link) — prune
        }
        try (var children = Files.list(dir)) {
            List<Path> subDirs = children.filter(Files::isDirectory)
                    .filter(p -> !isExcluded(p, excludeDirs))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path sub : subDirs) {
                collectMarkerDirSafely(sub, depth + 1, excludeDirs, maxDepth, visitedReal, found);
            }
        }
    }

    /**
     * True when {@code dir}'s bare name (case-insensitive) is configured via
     * {@code prjxp.hub.scan-exclude-dir-names}. Such directories are pruned before recursion, so heavy
     * non-project subtrees never slow down — or, on a single I/O hiccup, jeopardize — the discovery
     * of markers elsewhere in the tree.
     */
    private boolean isExcluded(Path dir, Set<String> excludeDirs) {
        String name = dir.getFileName().toString();
        return excludeDirs.stream().anyMatch(excluded -> excluded.equalsIgnoreCase(name));
    }

    private void registerMarkerDir(Path dir, Map<String, Path> found) {
        String name = configParser.parse(dir, dir.getFileName().toString()).getName();   // yaml name or directory name
        if (found.containsKey(name)) {
            log.warn("Duplicate project name '{}' — keeping {}", name, found.get(name));
        } else {
            found.put(name, dir);
        }
    }
}
