package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Parses {@code <projectDir>/prjxp.yaml} into a {@link ProjectDefinition}.
 * A missing or empty file yields all defaults; unparseable content is logged and falls back to defaults.
 * Pure utility — NOT hub-conditional, so it can be reused by any importer.
 */
@Component
public class ProjectConfigFileParser {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigFileParser.class);
    private static final String CONFIG_FILE_NAME = "prjxp.yaml";

    /** Parses <projectDir>/prjxp.yaml; missing/empty file -> all defaults. Never throws on bad content (log + defaults). */
    public ProjectDefinition parse(Path projectDir, String defaultName) {
        Path configFile = projectDir.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configFile)) {
            return defaults(defaultName);
        }
        try {
            String content = Files.readString(configFile);
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
            log.warn("Could not parse {} — falling back to defaults: {}", configFile, e.toString());
            return defaults(defaultName);
        }
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
