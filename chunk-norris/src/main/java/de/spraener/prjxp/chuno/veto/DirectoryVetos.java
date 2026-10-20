package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.annotations.ChunkVeto;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Vetoes files that sit under a directory whose name is listed in the project's
 * {@code excludeDirNames} (any path segment, case-insensitive) — unless the path is inside
 * a source directory ({@code sourceDirNames}, default {@code src}). This is what keeps vendor
 * trees like {@code node_modules} and build output like {@code target} out of the index, while
 * preserving source packages that merely happen to be named like build directories
 * (e.g. {@code src/.../target/CodeTarget.java}).
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "no-standard-vetos",
        havingValue = "false",      // active by default, like StandardVetos
        matchIfMissing = true
)
public class DirectoryVetos {
    private final PrjXPConfig cfg;
    private final VetoContext vetoContext;

    @ChunkVeto
    public boolean isExcludedDir(Path p) {
        ProjectDefinition def = resolveDefinition();
        if (def == null || !StringUtils.hasText(def.getExcludeDirNames())) {
            return false;
        }
        return scan(p, toSet(def.getExcludeDirNames()), sourceDirNames(def));
    }

    /**
     * True if the path is inside a source directory, i.e. any path segment matches the
     * project's {@code sourceDirNames} (default {@code src}, case-insensitive).
     * Deliberately NOT a {@link ChunkVeto}: "is source" is the opposite of a veto.
     */
    public boolean isSourceDirectory(Path p) {
        ProjectDefinition def = resolveDefinition();
        if (def == null) {
            return false;
        }
        Set<String> source = sourceDirNames(def);
        for (int i = 0; i < p.getNameCount(); i++) {
            if (source.contains(p.getName(i).toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Positional left-to-right scan, shared with {@link StandardVetos#isBuildArtifact}:
     * entering a source directory makes the rest of the path sacred (monotonic flag);
     * an excluded directory name vetoes the path only while still outside the source tree.
     * So {@code src/.../target/Foo.java} is source, but {@code build/generated/src/Foo.java}
     * (generated or copied sources) is vetoed at {@code build}.
     */
    static boolean scan(Path p, Set<String> excluded, Set<String> sourceDirs) {
        boolean inSourceTree = false;
        for (int i = 0; i < p.getNameCount(); i++) {
            String seg = p.getName(i).toString().toLowerCase(Locale.ROOT);
            if (sourceDirs.contains(seg)) {
                inSourceTree = true;
            } else if (!inSourceTree && excluded.contains(seg)) {
                return true;
            }
        }
        return false;
    }

    private ProjectDefinition resolveDefinition() {
        ProjectDefinition def = vetoContext.get();
        if (def == null) {
            def = cfg.getActiveProject().orElse(null);
        }
        return def;
    }

    /** The project's source-dir names, falling back to the default when unset. */
    private static Set<String> sourceDirNames(ProjectDefinition def) {
        String csv = def.getSourceDirNames();
        return StringUtils.hasText(csv) ? toSet(csv) : toSet(ProjectDefinition.DEFAULT_SOURCE_DIR_NAMES);
    }

    /** Splits a comma-separated list into a trimmed, lower-cased set (order-preserving); null-safe. */
    static Set<String> toSet(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null) {
            return set;
        }
        for (String part : csv.split(",")) {
            String s = part.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) {
                set.add(s);
            }
        }
        return set;
    }
}
