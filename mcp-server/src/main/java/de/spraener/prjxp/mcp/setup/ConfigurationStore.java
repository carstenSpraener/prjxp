package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.config.ConfigFileLocator;
import de.spraener.prjxp.common.config.ConfigSecretResolver;
import de.spraener.prjxp.common.config.EnvFileWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Service
public class ConfigurationStore {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path yamlPath;
    private final Path envPath;
    private final Environment environment;
    private final Clock clock;
    private final ConfigSecretResolver secrets;
    private final EnvFileWriter envWriter;
    private final byte[] salt = new byte[16];
    private final Map<String, Version> versions = new LinkedHashMap<>();
    private final Map<String, Object> activatedRoot;

    @Autowired
    public ConfigurationStore(Environment environment) {
        this(locator(environment), environment);
    }

    private ConfigurationStore(ConfigFileLocator locator, Environment environment) {
        this(locator.getApplicationYamlPath(), locator.getEnvFilePath(), environment, Clock.systemUTC());
    }

    ConfigurationStore(Path yamlPath, Path envPath, Environment environment, Clock clock) {
        this.yamlPath = yamlPath.toAbsolutePath().normalize();
        this.envPath = envPath.toAbsolutePath().normalize();
        this.environment = environment;
        this.clock = clock;
        this.secrets = new ConfigSecretResolver(this.envPath);
        this.envWriter = new EnvFileWriter(this.envPath);
        new SecureRandom().nextBytes(salt);
        this.activatedRoot = readSnapshot().root();
    }

    public Path getApplicationYamlPath() { return yamlPath; }
    public Path getEnvFilePath() { return envPath; }
    public String resolve(String value) throws IOException { return secrets.resolve(value); }
    Map<String, Object> activatedRoot() { return SetupData.map(activatedRoot); }

    public Snapshot readSnapshot() { return read(Function.identity()); }

    public <T> T read(Function<Snapshot, T> reader) {
        return locked(() -> {
            Snapshot current = snapshot();
            T result = reader.apply(current);
            if (!snapshot().fingerprint.equals(current.fingerprint)) {
                throw new SetupException("CONFIG_CHANGED", null, 409, "Configuration changed during read; reload");
            }
            return result;
        });
    }

    public Snapshot update(String version, Function<Snapshot, Update> mutation) {
        return update(version, mutation, Function.identity());
    }

    public <T> T update(String version, Function<Snapshot, Update> mutation, Function<Snapshot, T> reader) {
        return locked(() -> {
            Snapshot current = snapshot();
            Version registered = versions.get(version);
            if (registered == null || !registered.fingerprint().equals(current.fingerprint)) {
                throw new SetupException("CONFIG_CHANGED", null, 409, "Configuration changed; reload before saving");
            }
            Update update = mutation.apply(current);
            SetupData.validate(update.root());
            assertWritable(current.root(), update.root());
            if (!snapshot().fingerprint.equals(current.fingerprint)) {
                throw new SetupException("CONFIG_CHANGED", null, 409, "Configuration changed before commit; reload");
            }
            String yaml = yaml().dump(update.root());
            try {
                if (!update.envEntries().isEmpty()) {
                    try { envWriter.upsert(update.envEntries()); }
                    catch (IllegalArgumentException error) {
                        throw new SetupException("INVALID_REQUEST", null, 400, "Secret cannot be represented safely in the environment file");
                    }
                }
                if (!current.root().equals(update.root())) writeYaml(yaml);
            } catch (IOException error) {
                throw new SetupException("CONFIG_WRITE_FAILED", null, 500, "Cannot commit setup configuration");
            }
            return reader.apply(snapshot());
        });
    }

    protected void writeYaml(String text) throws IOException { new YamlWriter(yamlPath).write(text); }

    public <T> T bind(Map<String, Object> root, String prefix, Class<T> type) {
        return new Binder(sources(root), value -> {
            try { return value instanceof String text ? resolve(text) : value; }
            catch (IOException error) { throw ioError(); }
        }).bindOrCreate(prefix, Bindable.of(type));
    }

    public <T> T bindForDisplay(Map<String, Object> root, String prefix, Class<T> type) {
        return new Binder(sources(root), value -> {
            if (!(value instanceof String text)) return value;
            try { return resolve(text); }
            catch (IllegalArgumentException missing) {
                if (missing.getMessage() != null && missing.getMessage().startsWith("MISSING_SECRET:")) return text;
                throw missing;
            }
            catch (IOException error) { throw ioError(); }
        }).bindOrCreate(prefix, Bindable.of(type));
    }

    <T> T bindOrDefault(Map<String, Object> root, String prefix, Class<T> type, T fallback) {
        return new Binder(sources(root), value -> {
            try { return value instanceof String text ? resolve(text) : value; }
            catch (IOException error) { throw ioError(); }
        }).bind(prefix, Bindable.of(type)).orElse(fallback);
    }

    private List<ConfigurationPropertySource> sources(Map<String, Object> root) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", root, flat);
        ConfigurationPropertySource file = new MapConfigurationPropertySource(flat);
        List<ConfigurationPropertySource> result = new ArrayList<>();
        boolean inserted = false;
        boolean hasEditable = hasEditableSource();
        for (PropertySource<?> source : propertySources()) {
            if ("configurationProperties".equals(source.getName())) continue;
            if (editable(source)) {
                if (!inserted) result.add(file);
                inserted = true;
            } else {
                if (!hasEditable && !inserted && source.getName().startsWith("Config resource")) {
                    result.add(file);
                    inserted = true;
                }
                ConfigurationPropertySources.from(source).forEach(result::add);
            }
        }
        if (!inserted) result.add(file);
        return result;
    }

    private void assertWritable(Map<String, Object> before, Map<String, Object> after) throws IOException {
        Map<String, Object> old = new LinkedHashMap<>(), changed = new LinkedHashMap<>();
        flatten("", before, old);
        flatten("", after, changed);
        Set<String> keys = new LinkedHashSet<>(old.keySet());
        keys.addAll(changed.keySet());
        boolean hasEditable = hasEditableSource();
        for (PropertySource<?> source : propertySources()) {
            if ("configurationProperties".equals(source.getName())) continue;
            if (editable(source) || (!hasEditable && source.getName().startsWith("Config resource"))) break;
            for (String key : keys) {
                if (!key.startsWith("prjxp.") || key.startsWith("prjxp.setup-state.")
                        || Objects.equals(old.get(key), changed.get(key))) continue;
                Object override = null;
                var name = org.springframework.boot.context.properties.source.ConfigurationPropertyName.adapt(key, '.');
                for (ConfigurationPropertySource properties : ConfigurationPropertySources.from(source)) {
                    var property = properties.getConfigurationProperty(name);
                    if (property != null) override = property.getValue();
                }
                if (override != null && !Objects.equals(resolve(Objects.toString(override)),
                        resolve(Objects.toString(changed.get(key), null)))) {
                    throw new SetupException("CONFIG_OVERRIDDEN", null, 409, "Deployment overrides property: " + key);
                }
            }
        }
    }

    private Iterable<PropertySource<?>> propertySources() {
        return environment instanceof ConfigurableEnvironment configurable
                ? configurable.getPropertySources() : List.of();
    }

    private boolean hasEditableSource() {
        for (PropertySource<?> source : propertySources()) if (editable(source)) return true;
        return false;
    }

    private boolean editable(PropertySource<?> source) {
        if (source instanceof EnumerablePropertySource<?> enumerable) {
            for (String name : enumerable.getPropertyNames()) {
                Object origin = OriginLookup.getOrigin(source, name);
                if (origin instanceof TextResourceOrigin text) {
                    try {
                        if (text.getResource().isFile()
                                && text.getResource().getFile().toPath().toAbsolutePath().normalize().equals(yamlPath)) return true;
                    } catch (IOException error) { throw ioError(); }
                    break;
                }
            }
        }
        return false;
    }

    private Snapshot snapshot() throws IOException {
        String text = Files.exists(yamlPath) ? Files.readString(yamlPath) : null;
        Map<String, Object> root;
        try {
            root = text == null ? new LinkedHashMap<>() : SetupData.map(yaml().load(text));
            SetupData.validate(root);
        } catch (YAMLException error) { throw SetupData.invalid(); }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("yaml", text);
        material.put("env", Files.exists(envPath) ? Files.readString(envPath) : null);
        for (PropertySource<?> source : propertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("prjxp.") || name.startsWith("PRJXP_")) {
                        material.put(source.getName() + ":" + name, source.getProperty(name));
                    }
                }
            }
        }
        var references = java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)(?::[^}]*)?}")
                .matcher(Objects.toString(text, "") + Objects.toString(material.get("env"), ""));
        while (references.find()) material.put("reference:" + references.group(1), resolve("${" + references.group(1) + ":}"));
        String fingerprint = Base64.getEncoder().encodeToString(
                SetupTestEvidenceService.digest(salt, SetupData.canonical(material)));
        Instant oldest = clock.instant().minusSeconds(1800);
        versions.values().removeIf(value -> !value.created().isAfter(oldest));
        String token = versions.entrySet().stream().filter(entry -> entry.getValue().fingerprint().equals(fingerprint))
                .map(Map.Entry::getKey).findFirst().orElseGet(() -> {
                    byte[] bytes = new byte[16];
                    new SecureRandom().nextBytes(bytes);
                    String next = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    versions.put(next, new Version(fingerprint, clock.instant()));
                    return next;
                });
        while (versions.size() > 256) versions.remove(versions.keySet().iterator().next());
        return new Snapshot(token, root, fingerprint);
    }

    private <T> T locked(IoSupplier<T> action) {
        ReentrantLock lock = LOCKS.computeIfAbsent(yamlPath, path -> new ReentrantLock());
        lock.lock();
        try {
            if (lock.getHoldCount() > 1) return action.get();
            Files.createDirectories(yamlPath.getParent());
            try (FileChannel channel = FileChannel.open(yamlPath.resolveSibling(yamlPath.getFileName() + ".setup.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException error) { throw ioError(); }
        finally { lock.unlock(); }
    }

    private SetupException ioError() {
        return new SetupException("CONFIG_READ_FAILED", null, 500, "Cannot read setup configuration");
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        options.setCodePointLimit(3 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }

    private static ConfigFileLocator locator(Environment environment) {
        List<String> args = new ArrayList<>();
        for (String key : List.of("spring.config.location", "spring.config.additional-location")) {
            String value = environment.getProperty(key);
            if (value != null) args.add("--" + key + "=" + value);
        }
        return new ConfigFileLocator(args.toArray(String[]::new));
    }

    static void flatten(String prefix, Object value, Map<String, Object> result) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, item, result));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flatten(prefix + "[" + i + "]", list.get(i), result);
        } else if (value != null) result.put(prefix, value);
    }

    public static final class Snapshot {
        private final String configVersion;
        private final Map<String, Object> root;
        private final String fingerprint;
        private Snapshot(String version, Map<String, Object> root, String fingerprint) {
            this.configVersion = version;
            this.root = SetupData.map(root);
            this.fingerprint = fingerprint;
        }
        public String configVersion() { return configVersion; }
        public Map<String, Object> root() { return SetupData.map(root); }
        @Override public String toString() { return "ConfigurationSnapshot[redacted]"; }
    }

    public record Update(Map<String, Object> root, Map<String, String> envEntries) {
        @Override public String toString() { return "ConfigurationUpdate[redacted]"; }
    }
    private record Version(String fingerprint, Instant created) {}
    private interface IoSupplier<T> { T get() throws IOException; }
    private static class YamlWriter extends EnvFileWriter {
        YamlWriter(Path path) { super(path); }
        void write(String text) throws IOException { writeAtomically(text); }
        @Override protected void preparePermissions(Path temporary, Path target) throws IOException {
            prepareOwnerOnlyPermissions(temporary);
        }
    }
}
