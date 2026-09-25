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
            def.setTibedBatchSize(intOf(map.get("tibedBatchSize"), 32));
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

    private static ProjectDefinition defaults(String name) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setRootDir(".");
        def.setJsonlFile("px-chunks.jsonl");
        def.setChunoWhiteList("java,ts");
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
}
