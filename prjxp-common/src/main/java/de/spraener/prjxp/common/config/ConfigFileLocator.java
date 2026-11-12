package de.spraener.prjxp.common.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
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

        Set<Path> externalSources = new LinkedHashSet<>();
        boolean explicitSpring = false;
        for (String key : new String[]{"spring.config.location", "spring.config.additional-location"}) {
            String setting = springSetting(key, properties, environment, args);
            if (setting != null) {
                explicitSpring = true;
                for (String source : setting.split("[,;]")) {
                    String location = source.trim().replaceFirst("^optional:", "");
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
                    if (!path.toString().endsWith(".yaml") && !path.toString().endsWith(".yml")) {
                        throw new IllegalArgumentException("spring.config must identify a single editable YAML file");
                    }
                    externalSources.add(path.toAbsolutePath().normalize());
                }
            }
        }
        if (externalSources.size() > 1 || (explicitSpring && externalSources.isEmpty())) {
            throw new IllegalArgumentException("spring.config must identify a single editable YAML file");
        }
        Path selected = configOverride == null ? workingDirectory.resolve("application.yaml")
                : resolve(workingDirectory, configOverride);
        if (!externalSources.isEmpty()) {
            Path springPath = externalSources.iterator().next();
            if (configOverride != null && !springPath.equals(selected)) {
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
}
