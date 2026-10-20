package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Parses {@code <projectDir>/prjxp.yaml} (or {@code prjxp.yml}) into a {@link ProjectDefinition}.
 * A missing or empty file yields all defaults; unparseable content is logged and falls back to defaults.
 * Pure utility — NOT hub-conditional, so it can be reused by any importer.
 */
@Component
public class ProjectConfigFileParser {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigFileParser.class);
    /** Marker file names in precedence order — yaml first. */
    private static final String[] CONFIG_FILE_NAMES = {"prjxp.yaml", "prjxp.yml"};
    /**
     * Marker file that tells the live-project scan to never enter a directory (and hence never descend
     * into its subtree either) — regardless of whether a {@code prjxp.yaml}/{@code .yml} also sits there.
     * Useful for pruning huge non-project subtrees (e.g. old VCS branch/tag checkouts) directly at the
     * source, without touching the hub's global {@code prjxp.hub.scan-exclude-dir-names} configuration.
     */
    public static final String EXCLUDE_MARKER_FILE_NAME = ".prjxp-exclude";

    /** Marker file that signals a pre-embedded project is ready for import.
     * A project with {@code embeddingsFile} set in its prjxp.yaml is only enqueued when this file exists,
     * preventing race conditions during file transfers (the JSONL may still be incomplete without it). */
    public static final String READY_MARKER_FILE_NAME = ".ready";

    /** Parses the project's marker file (yaml preferred over yml); missing/empty -> all defaults. Never throws on bad content (log + defaults). */
    public ProjectDefinition parse(Path projectDir, String defaultName) {
        Optional<Path> configFile = markerFile(projectDir);
        if (configFile.isEmpty()) {
            return defaults(defaultName);
        }
        try {
            String content = Files.readString(configFile.get());
            if (content.isBlank()) {
                return defaults(defaultName);
            }
            Object loaded = new Yaml().load(content);
            if (!(loaded instanceof Map<?, ?> map)) {
                return defaults(defaultName);
            }
            ProjectDefinition def = new ProjectDefinition();
            def.setName(stringOf(map.get("name"), defaultName));
            def.setRootDir(stringOf(map.get("rootDir"), "."));
            def.setJsonlFile(stringOf(map.get("jsonlFile"), "px-chunks.jsonl"));
            def.setChunoWhiteList(stringOf(map.get("chunoWhiteList"), "java,ts"));
            def.setExcludeDirNames(stringOf(map.get("excludeDirNames"), ProjectDefinition.DEFAULT_EXCLUDE_DIR_NAMES));
            def.setSourceDirNames(stringOf(map.get("sourceDirNames"), ProjectDefinition.DEFAULT_SOURCE_DIR_NAMES));
            def.setTibedBatchSize(intOf(map.get("tibedBatchSize"), 32));
            def.setFulltext(boolOf(map.get("fulltext"), false));
            def.setEmbeddingsFile(stringOf(map.get("embeddingsFile"), null));
            def.setPrompt(normalizePrompt(stringOf(map.get("prompt"), null)));
            return def;
        } catch (Exception e) {
            log.warn("Could not parse {} — falling back to defaults: {}", configFile.get(), e.toString());
            return defaults(defaultName);
        }
    }

    /** The existing marker file of a directory (prjxp.yaml preferred over prjxp.yml), if any. */
    public Optional<Path> markerFile(Path dir) {
        for (String fileName : CONFIG_FILE_NAMES) {
            Path candidate = dir.resolve(fileName);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** True when {@code dir} contains the {@value #EXCLUDE_MARKER_FILE_NAME} exclusion marker file. */
    public boolean isExcluded(Path dir) {
        return Files.exists(dir.resolve(EXCLUDE_MARKER_FILE_NAME));
    }

    /** True when {@code dir} contains the {@value #READY_MARKER_FILE_NAME} readiness marker file. */
    public boolean isReadyMarkerPresent(Path dir) {
        return Files.exists(dir.resolve(READY_MARKER_FILE_NAME));
    }

    private static ProjectDefinition defaults(String name) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setRootDir(".");
        def.setJsonlFile("px-chunks.jsonl");
        def.setChunoWhiteList("java,ts");
        def.setExcludeDirNames(ProjectDefinition.DEFAULT_EXCLUDE_DIR_NAMES);
        def.setSourceDirNames(ProjectDefinition.DEFAULT_SOURCE_DIR_NAMES);
        def.setTibedBatchSize(32);
        return def;
    }

    private static String stringOf(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            String s = String.valueOf(value).trim().toLowerCase();
            if (!s.isEmpty()) {
                return Boolean.parseBoolean(s);
            }
        }
        return fallback;
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // not a number — fall back to the default
            }
        }
        return fallback;
    }

    /** Saves a {@link ProjectDefinition} as YAML to the project directory.
     * Uses existing marker file if present, otherwise creates {@code prjxp.yaml}. */
    public void save(Path projectDir, ProjectDefinition definition) {
        Path target = markerFile(projectDir).orElseGet(() -> projectDir.resolve("prjxp.yaml"));
        try {
            StringBuilder yaml = new StringBuilder();
            appendLine(yaml, "name", definition.getName());
            appendLineIfNotBlank(yaml, "rootDir", definition.getRootDir());
            appendLineIfNotBlank(yaml, "jsonlFile", definition.getJsonlFile());
            appendLineIfNotBlank(yaml, "chunoWhiteList", definition.getChunoWhiteList());
            appendLineIfNotBlank(yaml, "excludeDirNames", definition.getExcludeDirNames());
            appendLineIfNotBlank(yaml, "sourceDirNames", definition.getSourceDirNames());
            if (definition.getTibedBatchSize() != 32) {
                yaml.append("tibedBatchSize: ").append(definition.getTibedBatchSize()).append("\n");
            }
            appendLineIfNotBlank(yaml, "embeddingsFile", definition.getEmbeddingsFile());
            if (definition.isTibedResetStore()) {
                yaml.append("tibedResetStore: true\n");
            }
            if (definition.isFulltext()) {
                yaml.append("fulltext: true\n");
            }
            appendPrompt(yaml, definition.getPrompt());

            Files.writeString(target, yaml.toString());
            log.info("Saved project config to {}", target);
        } catch (Exception e) {
            log.error("Failed to save project config for {} to {}: {}", definition.getName(), target, e.getMessage());
        }
    }

    private void appendLine(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(key).append(": ").append(escapeYamlValue(value)).append("\n");
        }
    }

    private void appendLineIfNotBlank(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(key).append(": ").append(escapeYamlValue(value)).append("\n");
        }
    }

    /** Simple YAML value escaping — quotes if the value contains special characters. */
    private String escapeYamlValue(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        // Quote if it contains characters that could be misinterpreted by YAML parsers
        if (value.matches(".*[\\: #{}\\[\\]|&*?<>!%@`].*") || value.startsWith("---") || value.equals("true") || value.equals("false")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    /** Normalizes a parsed prompt: strips trailing whitespace; blank -> null. */
    private static String normalizePrompt(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.stripTrailing();
        return stripped.isBlank() ? null : stripped;
    }

    /** Writes the prompt key: block scalar when multi-line, escaped single line otherwise; omitted when null/blank. */
    private void appendPrompt(StringBuilder sb, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        String value = prompt.stripTrailing();
        if (value.contains("\n")) {
            sb.append("prompt: |\n");
            for (String line : value.split("\n", -1)) {
                sb.append("  ").append(line).append("\n");
            }
        } else {
            appendLineIfNotBlank(sb, "prompt", value);
        }
    }
}
