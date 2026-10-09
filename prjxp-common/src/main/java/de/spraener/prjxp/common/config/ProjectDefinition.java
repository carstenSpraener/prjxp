package de.spraener.prjxp.common.config;

import lombok.Data;

import java.nio.file.Path;
import java.nio.file.Paths;

@Data
public class ProjectDefinition {
    private String name;
    private String rootDir;
    private String jsonlFile;
    private String chunoWhiteList = "java,ts";
    private int tibedBatchSize = 32;
    private boolean tibedResetStore = false;
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
