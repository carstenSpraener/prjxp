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
    private int tibedBatchSize = 50;
    private boolean tibedResetStore = false;

    /** jsonlFile resolved against rootDir (absolute paths pass through; null/blank -> null). */
    public String resolvedJsonlFile() {
        if (jsonlFile == null || jsonlFile.isBlank()) {
            return null;
        }
        Path p = Paths.get(jsonlFile);
        if (p.isAbsolute()) {
            return jsonlFile;
        }
        String root = (rootDir == null || rootDir.isBlank()) ? "." : rootDir;
        return Paths.get(root).resolve(jsonlFile).toString();
    }
}
