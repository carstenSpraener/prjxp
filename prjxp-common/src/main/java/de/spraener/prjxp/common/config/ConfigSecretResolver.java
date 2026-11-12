package de.spraener.prjxp.common.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ConfigSecretResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)(?::([^}]*))?}");
    private static final Map<String, String> IMPORTED_PROPERTIES = new ConcurrentHashMap<>();
    private final EnvFileWriter envFile;
    private final Function<String, String> properties;
    private final Function<String, String> environment;
    private final Map<String, String> imported;

    public ConfigSecretResolver(Path envFile) {
        this(envFile, System::getProperty, System::getenv, IMPORTED_PROPERTIES);
    }

    ConfigSecretResolver(Path envFile, Function<String, String> properties,
                         Function<String, String> environment, Map<String, String> imported) {
        this.envFile = new EnvFileWriter(envFile);
        this.properties = properties;
        this.environment = environment;
        this.imported = imported;
    }

    public String resolve(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return value;
        }
        var matcher = PLACEHOLDER.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        Map<String, String> entries = readEntries();
        StringBuilder result = new StringBuilder();
        do {
            String key = matcher.group(1);
            String replacement = lookup(key, entries);
            if (replacement == null) {
                replacement = matcher.group(2);
            }
            if (replacement == null) {
                throw new IllegalArgumentException("MISSING_SECRET: " + key);
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    public void importIntoSystemProperties() throws IOException {
        Map<String, String> entries = readEntries();
        synchronized (IMPORTED_PROPERTIES) {
            for (var entry : entries.entrySet()) {
                if (System.getProperty(entry.getKey()) == null) {
                    String value = environment.apply(entry.getKey());
                    if (value == null) {
                        value = entry.getValue();
                    }
                    System.setProperty(entry.getKey(), value);
                    IMPORTED_PROPERTIES.put(entry.getKey(), value);
                }
            }
        }
    }

    private String lookup(String key, Map<String, String> entries) {
        String value = properties.apply(key);
        if (value != null && !value.equals(imported.get(key))) {
            return value;
        }
        value = environment.apply(key);
        return value != null ? value : entries.get(key);
    }

    private Map<String, String> readEntries() throws IOException {
        Map<String, String> entries = envFile.read();
        Path path = envFile.getEnvFile();
        if (Files.exists(path)) {
            try {
                Dotenv.configure().directory(path.getParent().toString()).filename(path.getFileName().toString()).load();
            } catch (DotenvException error) {
                // The library includes the raw entry in its exception, which may contain a secret.
                throw new IOException("Malformed .env file at " + path);
            }
        }
        return entries;
    }
}
