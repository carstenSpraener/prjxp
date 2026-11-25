package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the {@code listConfig} catalog for a project without booting a Spring context:
 * a light scan of the project's YAML, the {@code @Value} keys in its config classes and
 * the configuration tables in its Markdown docs. This gives F4 ("which config parameters
 * exist?") one cheap call instead of eight full-text reads.
 */
@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ConfigCatalogService {

    private static final int MAX_PROPERTIES = 80;
    private static final Pattern VALUE_ANNOTATION = Pattern.compile(
            "@Value\\(\\s*\"\\$\\{([^}:\\s]+)(?::([^}]*))?}?\"\\s*\\)");
    private static final Pattern CONFIG_PREFIX = Pattern.compile(
            "@ConfigurationProperties\\(\\s*(?:prefix\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*private\\s+([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*(?:=.*)?;");

    private final ProjectRegistry projectRegistry;

    public ConfigCatalog list(String project) {
        try {
            projectRegistry.ensureSearchable(project);
        } catch (UnknownProjectException e) {
            return ConfigCatalog.unavailable(e.getMessage());
        }
        ProjectDefinition definition = projectRegistry.definitionOf(project);
        if (definition == null || definition.getRootDir() == null) {
            return ConfigCatalog.unavailable("Projektverzeichnis nicht verfügbar: " + project);
        }
        Path root = Path.of(definition.getRootDir());
        Map<String, ConfigProperty> byName = new LinkedHashMap<>();
        parseYaml(root, byName);
        parseJavaConfig(root, byName);
        parseMarkdown(root, byName);

        List<ConfigProperty> properties = byName.values().stream().limit(MAX_PROPERTIES).toList();
        return new ConfigCatalog(project, properties, null);
    }

    // ------------------------------------------------------------------ YAML

    private void parseYaml(Path root, Map<String, ConfigProperty> out) {
        for (Path yaml : findFiles(root, 4, p -> p.getFileName().toString().equals("application.yaml")
                || p.getFileName().toString().equals("application.yml"))) {
            try {
                parseYamlFile(root, yaml, out);
            } catch (IOException ignored) {
                // a broken config file must not break the catalog
            }
        }
    }

    private void parseYamlFile(Path root, Path file, Map<String, ConfigProperty> out) throws IOException {
        List<String> lines = Files.readAllLines(file);
        Deque<Integer> indents = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                continue; // YAML list item: not a scalar property
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            if (key.isEmpty() || key.startsWith("$")) {
                continue;
            }
            String value = trimmed.substring(colon + 1).trim();
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                keys.pop();
            }
            indents.push(indent);
            keys.push(key);
            if (value.isEmpty()) {
                continue; // parent node
            }
            String fullKey = String.join(".", reversed(keys));
            if (fullKey.startsWith("prjxp.")) {
                out.putIfAbsent(fullKey, new ConfigProperty(
                        fullKey, null, unquote(value), null, rel(root, file)));
            }
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ------------------------------------------------------------------ Java config classes

    private void parseJavaConfig(Path root, Map<String, ConfigProperty> out) {
        for (Path java : findFiles(root, 12, p -> p.getFileName().toString().endsWith(".java")
                && p.toString().contains("config"))) {
            try {
                String source = Files.readString(java);
                parseJavaSource(root, java, source, out);
            } catch (IOException ignored) {
                // unreadable source: skip
            }
        }
    }

    private void parseJavaSource(Path root, Path file, String source, Map<String, ConfigProperty> out) {
        Matcher valueMatcher = VALUE_ANNOTATION.matcher(source);
        while (valueMatcher.find()) {
            String name = valueMatcher.group(1).trim();
            String defaultValue = valueMatcher.group(2);
            out.putIfAbsent(name, new ConfigProperty(
                    name, null, defaultValue == null ? null : unquote(defaultValue.trim()), null, rel(root, file)));
        }

        Matcher prefixMatcher = CONFIG_PREFIX.matcher(source);
        if (!prefixMatcher.find()) {
            return;
        }
        String prefix = prefixMatcher.group(1);
        for (String line : source.lines().toList()) {
            Matcher field = FIELD.matcher(line);
            if (!field.find()) {
                continue;
            }
            String type = field.group(1);
            String name = prefix + "." + kebabCase(field.group(2));
            out.putIfAbsent(name, new ConfigProperty(name, type, null, null, rel(root, file)));
        }
    }

    private static String kebabCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ Markdown tables

    private void parseMarkdown(Path root, Map<String, ConfigProperty> out) {
        for (Path md : findFiles(root, 6, p -> p.getFileName().toString().equals("configuration.md"))) {
            try {
                for (String line : Files.readAllLines(md)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("|")) {
                        continue;
                    }
                    String[] cells = trimmed.split("\\|");
                    if (cells.length < 3) {
                        continue;
                    }
                    String name = stripBackticks(cells[1].trim());
                    if (!name.startsWith("prjxp.")) {
                        continue;
                    }
                    out.putIfAbsent(name, new ConfigProperty(
                            name,
                            cells.length > 2 ? emptyToNull(stripBackticks(cells[2].trim())) : null,
                            cells.length > 3 ? emptyToNull(stripBackticks(cells[3].trim())) : null,
                            cells.length > 4 ? emptyToNull(cells[4].trim()) : null,
                            rel(root, md)));
                }
            } catch (IOException ignored) {
                // skip unreadable docs
            }
        }
    }

    private static String stripBackticks(String value) {
        return value.replace("`", "").trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() || value.equals("-") ? null : value;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> findFiles(Path root, int maxDepth, java.util.function.Predicate<Path> filter) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            return stream.filter(Files::isRegularFile).filter(filter).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String rel(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static List<String> reversed(Deque<String> stack) {
        List<String> result = new ArrayList<>(stack);
        java.util.Collections.reverse(result);
        return result;
    }
}
