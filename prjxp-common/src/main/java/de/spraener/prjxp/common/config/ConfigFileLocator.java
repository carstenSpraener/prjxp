package de.spraener.prjxp.common.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ConfigFileLocator {
    private final Path applicationYamlPath;
    private final Path envFilePath;
    private final String springAdditionalLocation;

    public ConfigFileLocator() {
        this(new String[0]);
    }

    public ConfigFileLocator(String[] args) {
        this(Path.of(System.getProperty("user.dir")), System::getProperty, System::getenv, args);
    }

    ConfigFileLocator(Path directory, Function<String, String> properties,
                      Function<String, String> environment, String... args) {
        Path workingDirectory = directory.toAbsolutePath().normalize();
        String configOverride = lookup("PRJXP_CONFIG_FILE", properties, environment);
        String envOverride = lookup("PRJXP_ENV_FILE", properties, environment);
        envFilePath = resolve(workingDirectory, envOverride == null ? ".env" : envOverride);

        List<Path> candidates = new ArrayList<>();
        List<Boolean> optional = new ArrayList<>();
        boolean explicitSpring = false;
        for (String key : new String[]{"spring.config.location", "spring.config.additional-location"}) {
            String setting = springSetting(key, properties, environment, args);
            if (setting != null) {
                explicitSpring = true;
                for (String source : setting.split("[,;]")) {
                    String raw = source.trim();
                    if (raw.isEmpty()) {
                        continue;
                    }
                    boolean isOptional = raw.startsWith("optional:");
                    String location = raw.replaceFirst("^optional:", "");
                    if (location.startsWith("classpath:")) {
                        continue;
                    }
                    Path path;
                    if (location.startsWith("file:")) {
                        String fileLocation = location.substring(5);
                        path = fileLocation.startsWith("/")
                                ? Path.of(URI.create(location)) : resolve(workingDirectory, fileLocation);
                    } else {
                        path = resolve(workingDirectory, location);
                    }
                    Path file = toEditableYamlFile(path);
                    if (file == null) {
                        throw new IllegalArgumentException("spring.config must identify a single editable YAML file");
                    }
                    Path normalized = file.toAbsolutePath().normalize();
                    if (!candidates.contains(normalized)) {
                        candidates.add(normalized);
                        optional.add(isOptional);
                    }
                }
            }
        }
        Path selected;
        if (candidates.isEmpty()) {
            if (explicitSpring) {
                throw new IllegalArgumentException("spring.config must identify a single editable YAML file");
            }
            selected = configOverride == null ? workingDirectory.resolve("application.yaml")
                    : resolve(workingDirectory, configOverride);
        } else {
            Path springPath = selectSingleCandidate(candidates, optional);
            if (configOverride != null && !springPath.equals(resolve(workingDirectory, configOverride))) {
                throw new IllegalArgumentException("PRJXP_CONFIG_FILE conflicts with spring.config file");
            }
            selected = springPath;
        }
        applicationYamlPath = selected;
        springAdditionalLocation = explicitSpring ? null : "optional:" + applicationYamlPath.toUri();
    }

    public Path getApplicationYamlPath() {
        return applicationYamlPath;
    }

    public Path getEnvFilePath() {
        return envFilePath;
    }

    public String getSpringAdditionalLocation() {
        return springAdditionalLocation;
    }

    private static String lookup(String key, Function<String, String> properties, Function<String, String> environment) {
        String value = properties.apply(key);
        if (value == null || value.isBlank()) {
            value = environment.apply(key);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String springSetting(String key, Function<String, String> properties,
                                        Function<String, String> environment, String[] args) {
        String result = lookup(key, properties,
                property -> environment.apply(property.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_')));
        if (args != null) {
            for (String arg : args) {
                if (arg.equals("--" + key)) {
                    throw new IllegalArgumentException("Missing value for " + key);
                }
                if (arg.startsWith("--" + key + "=")) {
                    result = arg.substring(key.length() + 3);
                    if (result.isBlank()) {
                        throw new IllegalArgumentException("Missing value for " + key);
                    }
                }
            }
        }
        return result;
    }

    private static Path resolve(Path directory, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
    }

    /**
     * Resolves a {@code spring.config} location to the single editable YAML file it identifies.
     * A location that already names a YAML file is used as-is; a directory (or an
     * extensionless path, e.g. {@code file:/app/}) resolves to its conventional
     * {@code application.yaml}. Returns {@code null} for a file with an unsupported
     * (non-YAML) extension, which the caller rejects.
     */
    private static Path toEditableYamlFile(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return path;
        }
        if (Files.isDirectory(path) || noFileExtension(name)) {
            return path.resolve("application.yaml");
        }
        return null;
    }

    private static boolean noFileExtension(String name) {
        return name.lastIndexOf('.') <= 0;
    }

    /**
     * Selects the single effective config file. A lone candidate wins; a chain of
     * {@code optional:} locations resolves first-exists-wins (mirroring Spring's
     * fallback semantics); any other multi-source combination is rejected.
     */
    private static Path selectSingleCandidate(List<Path> candidates, List<Boolean> optional) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        boolean allOptional = optional.stream().allMatch(Boolean::booleanValue);
        if (!allOptional) {
            throw new IllegalArgumentException("spring.config must identify a single editable YAML file");
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
