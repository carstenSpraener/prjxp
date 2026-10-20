package de.spraener.prjxp.common.config;

import lombok.Data;

import java.nio.file.Path;
import java.nio.file.Paths;

@Data
public class ProjectDefinition {
    /**
     * Default directory names that are never indexed, matched against any path segment
     * (case-insensitive), unless the path is inside a source directory (see {@link #sourceDirNames}).
     * Covers common vendor/VCS/build/IDE directories. {@code build} and {@code target} are listed
     * here and share the source-directory exception with {@code StandardVetos.isBuildArtifact}.
     */
    public static final String DEFAULT_EXCLUDE_DIR_NAMES =
            "node_modules,.git,.svn,.hg,vendor,dist,coverage,.idea,.vscode,__pycache__,target,build,bin,obj,debug,release";

    /**
     * Default directory names that mark a source tree. Once a path enters a source directory,
     * {@link #excludeDirNames} no longer applies — a package named {@code target} inside {@code src}
     * is source code, not a build artifact.
     */
    public static final String DEFAULT_SOURCE_DIR_NAMES = "src";

    private String name;
    private String rootDir;
    private String jsonlFile;
    private String chunoWhiteList = "java,ts";
    /** Comma-separated directory names never indexed outside a source directory (any path segment, case-insensitive). */
    private String excludeDirNames = DEFAULT_EXCLUDE_DIR_NAMES;
    /** Comma-separated directory names that mark a source tree; inside them, excludeDirNames does not apply. */
    private String sourceDirNames = DEFAULT_SOURCE_DIR_NAMES;
    private int tibedBatchSize = 32;
    private boolean tibedResetStore = false;
    /** Optional: parallel full-text copy of every indexed file for lossless readFile (default off). */
    private boolean fulltext = false;
    /** Optional standing instructions for chat, prepended to every conversation. Supports ${ENV} placeholders. */
    private String prompt;
    /** Optional path to a JSONL file containing pre-computed embeddings (EmbeddedChunkRecord format).
     * When set, the pipeline skips chunking and imports these embeddings directly. Relative paths are resolved against rootDir. */
    private String embeddingsFile;

    /** embeddingsFile resolved against rootDir (absolute paths pass through; null/blank -> null). */
    public String resolvedEmbeddingsFile() {
        if (embeddingsFile == null || embeddingsFile.isBlank()) {
            return null;
        }
        Path p = Paths.get(embeddingsFile);
        if (p.isAbsolute() || isAbsolutePathPortable(embeddingsFile)) {
            return embeddingsFile;
        }
        String root = (rootDir == null || rootDir.isBlank()) ? "." : rootDir;
        return Paths.get(root).resolve(embeddingsFile).toString();
    }

    /** jsonlFile resolved against rootDir (absolute paths pass through; null/blank -> null). */
    public String resolvedJsonlFile() {
        if (jsonlFile == null || jsonlFile.isBlank()) {
            return null;
        }
        Path p = Paths.get(jsonlFile);
        if (p.isAbsolute() || isAbsolutePathPortable(jsonlFile)) {
            return jsonlFile;
        }
        String root = (rootDir == null || rootDir.isBlank()) ? "." : rootDir;
        return Paths.get(root).resolve(jsonlFile).toString();
    }

    private boolean isAbsolutePathPortable(String value) {
        if (value.startsWith("/")) {
            return true;
        }
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\');
    }
}
