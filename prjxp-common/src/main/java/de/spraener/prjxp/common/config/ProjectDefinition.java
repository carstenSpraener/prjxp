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
     * {@code .prjxp-data} is prjxp's own working directory (Lucene index, transfer downloads,
     * e2e scratch files); a project tree that contains it must not index it.
     */
    public static final String DEFAULT_EXCLUDE_DIR_NAMES =
            "node_modules,.git,.svn,.hg,vendor,dist,coverage,.idea,.vscode,__pycache__,target,build,bin,obj,debug,release,.prjxp-data";

    /**
     * Default directory names that mark a source tree. Once a path enters a source directory,
     * {@link #excludeDirNames} no longer applies — a package named {@code target} inside {@code src}
     * is source code, not a build artifact.
     */
    public static final String DEFAULT_SOURCE_DIR_NAMES = "src";

    private String name;
    private String rootDir;
    private String jsonlFile;
    /** Optional path to the code knowledge graph artifact. When unset, derived from the JSONL file ({@code px-chunks.jsonl} → {@code px-chunks.graph.json}). */
    private String graphFile;
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
    /** When true, the hub resolves this project's build dependencies and embeds them as independent library projects. */
    private boolean embedDependencies = false;
    /** Max dependency hops to resolve (default 2). */
    private int dependencyDepth = 2;
    /** Comma-separated GroupId prefixes to import (empty = no filter). */
    private String dependencyWhitelist = "";
    /** Set on hub-generated library project yamls: the GAV this project was materialized from. */
    private String mavenDependency;
    /** Origin marker of a hub-cloned GitHub project (the git URL); null for non-GitHub projects. */
    private String gitUrl = null;
    /** Encrypted PAT for a private GitHub repo (v1:<b64salt>:<b64iv>:<b64ct>); null for public repos. */
    private String gitToken = null;
    /** Per-project opt-out for live updates (prjxp.yaml {@code liveUpdate: false}). Default true. */
    private boolean liveUpdate = true;

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

    /**
     * graphFile resolved against rootDir (absolute paths pass through). When {@code graphFile}
     * is unset, it is derived from the JSONL file: {@code px-chunks.jsonl} → {@code px-chunks.graph.json}.
     * Returns null when neither is set.
     */
    public String resolvedGraphFile() {
        String raw = (graphFile != null && !graphFile.isBlank()) ? graphFile : deriveGraphFileFromJsonl();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = Paths.get(raw);
        if (p.isAbsolute() || isAbsolutePathPortable(raw)) {
            return raw;
        }
        String root = (rootDir == null || rootDir.isBlank()) ? "." : rootDir;
        return Paths.get(root).resolve(raw).toString();
    }

    /** Derives the graph file path from the configured JSONL file (same base, {@code .graph.json} extension). */
    private String deriveGraphFileFromJsonl() {
        if (jsonlFile == null || jsonlFile.isBlank()) {
            return null;
        }
        return jsonlFile.endsWith(".jsonl")
                ? jsonlFile.substring(0, jsonlFile.length() - ".jsonl".length()) + ".graph.json"
                : jsonlFile + ".graph.json";
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
