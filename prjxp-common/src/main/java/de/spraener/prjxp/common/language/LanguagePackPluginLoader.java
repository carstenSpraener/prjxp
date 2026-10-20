package de.spraener.prjxp.common.language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.function.Predicate;

/**
 * Loads external {@link LanguagePack}s from plugin JARs.
 *
 * <p>Each JAR in the configured plugin directory gets its own isolating
 * class loader. Delegation rule: <b>parent-first</b> for
 * {@code de.spraener.prjxp.**} (the whole host API) and {@code org.slf4j.**}
 * (host logging), <b>child-first</b> for everything else (private plugin
 * libraries such as ANTLR stay isolated). This keeps every host API type on
 * the host's class identity — even if a plugin JAR accidentally bundles a
 * copy of the API — while private dependencies remain version-isolated.
 *
 * <p>Every provider is validated at load time (contract methods + identity
 * check); a broken plugin is rejected with a clear diagnostic and never
 * blocks the other JARs. No hot reload: a restart picks up new/changed JARs.
 *
 * <p>The loader owns the created class loaders; call {@link #close()} on
 * shutdown.
 */
public final class LanguagePackPluginLoader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LanguagePackPluginLoader.class);

    /**
     * Default delegation rule: parent-first for all host API classes
     * ({@code de.spraener.prjxp.**}) and the logging facade ({@code org.slf4j.**}),
     * child-first for everything else.
     */
    public static Predicate<String> defaultDelegation() {
        return name -> name.startsWith("de.spraener.prjxp.") || name.startsWith("org.slf4j.");
    }

    private final File pluginDir;
    private final Predicate<String> parentFirst;
    private final List<PluginClassLoader> classLoaders = new ArrayList<>();
    private List<LanguagePack> loadedPacks;

    public LanguagePackPluginLoader(File pluginDir) {
        this(pluginDir, defaultDelegation());
    }

    /** Package-private: allows tests to inject a (broken) delegation rule. */
    LanguagePackPluginLoader(File pluginDir, Predicate<String> parentFirst) {
        this.pluginDir = pluginDir;
        this.parentFirst = parentFirst;
    }

    /**
     * Loads all valid packs from all plugin JARs in the configured directory.
     * A missing or empty directory yields an empty list. A broken JAR is
     * logged and skipped; it never blocks the other JARs.
     *
     * <p>Loads once: subsequent calls return the cached result (no hot reload
     * by design — a restart picks up new/changed JARs).
     */
    public List<LanguagePack> loadAll() {
        if (loadedPacks != null) {
            return loadedPacks;
        }
        List<LanguagePack> packs = new ArrayList<>();
        File[] jars = pluginDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) {
            log.debug("Plugin directory {} does not exist — no external LanguagePacks", pluginDir);
        } else {
            for (File jar : jars) {
                loadJar(jar, packs);
            }
        }
        loadedPacks = List.copyOf(packs);
        return loadedPacks;
    }

    private void loadJar(File jar, List<LanguagePack> packs) {
        PluginClassLoader loader;
        try {
            loader = new PluginClassLoader(jar, LanguagePackPluginLoader.class.getClassLoader(), parentFirst);
        } catch (IOException e) {
            log.error("Skipping plugin JAR {}: cannot open ({})", jar.getName(), e.toString());
            return;
        }
        classLoaders.add(loader);
        try {
            List<ServiceLoader.Provider<LanguagePackProvider>> providers =
                    ServiceLoader.load(LanguagePackProvider.class, loader).stream().toList();
            for (ServiceLoader.Provider<LanguagePackProvider> provider : providers) {
                LanguagePack pack = instantiateAndValidate(jar, provider);
                if (pack != null) {
                    packs.add(pack);
                }
            }
        } catch (Throwable t) {
            boolean identityBreak = t instanceof LinkageError || t instanceof ServiceConfigurationError;
            log.error("Skipping plugin JAR {}: provider discovery failed ({}){}",
                    jar.getName(), t.toString(),
                    identityBreak ? " — likely a class identity break: the plugin JAR must not bundle de.spraener.prjxp.** or org.slf4j.** classes" : "");
        }
    }

    private LanguagePack instantiateAndValidate(File jar, ServiceLoader.Provider<LanguagePackProvider> provider) {
        LanguagePackProvider providerInstance;
        try {
            providerInstance = provider.get();
        } catch (Throwable t) {
            log.error("Rejecting plugin {} ({}): provider instantiation failed ({})",
                    jar.getName(), provider.type(), t.toString());
            return null;
        }
        Object created;
        try {
            created = providerInstance.create();
        } catch (Throwable t) {
            log.error("Rejecting plugin {} ({}): create() failed ({})",
                    jar.getName(), providerInstance.getClass().getName(), t.toString());
            return null;
        }
        if (!(created instanceof LanguagePack pack)) {
            logIdentityBreak(jar, created);
            return null;
        }
        try {
            // Force full contract execution: catches linkage errors (NoClassDefFoundError, ...) at startup
            pack.language();
            pack.mimeType();
            pack.retriever();
            pack.listPostWalkChunkers().toList();
        } catch (Throwable t) {
            log.error("Rejecting plugin {} ({}): contract validation failed ({})",
                    jar.getName(), pack.getClass().getName(), t.toString());
            return null;
        }
        log.info("Loaded external LanguagePack '{}' ({}) from {}", pack.language(), pack.getClass().getName(), jar.getName());
        return pack;
    }

    private void logIdentityBreak(File jar, Object created) {
        Class<?> createdClass = created == null ? null : created.getClass();
        log.error("Rejecting plugin {}: class identity break — created object {} (loaded by {}) is not the host's LanguagePack (owned by {}). "
                        + "The plugin JAR must not bundle de.spraener.prjxp.** classes; check the delegation rule.",
                jar.getName(),
                createdClass == null ? "null" : createdClass.getName(),
                createdClass == null ? "n/a" : createdClass.getClassLoader(),
                LanguagePack.class.getClassLoader());
    }

    /** The configured plugin directory. */
    public File pluginDir() {
        return pluginDir;
    }

    /** The packs loaded by {@link #loadAll()} — empty until the first call. */
    public List<LanguagePack> loadedPacks() {
        return loadedPacks == null ? List.of() : loadedPacks;
    }

    /** The class loaders created so far, for shutdown handling. */
    public List<ClassLoader> classLoaders() {
        return List.copyOf(classLoaders);
    }

    /** Closes all plugin class loaders. Idempotent. */
    @Override
    public void close() {
        for (PluginClassLoader loader : classLoaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.warn("Error closing plugin class loader for {}: {}", loader.jarName(), e.toString());
            }
        }
        classLoaders.clear();
    }

    /**
     * Isolating class loader for a single plugin JAR.
     * Delegates according to the configured rule: parent-first for the
     * host API + slf4j, child-first for everything else.
     */
    private static final class PluginClassLoader extends ClassLoader implements AutoCloseable {

        private final File jarFile;
        private final JarFile jar;
        private final Predicate<String> parentFirst;

        PluginClassLoader(File jarFile, ClassLoader parent, Predicate<String> parentFirst) throws IOException {
            super(parent);
            this.jarFile = jarFile;
            this.jar = new JarFile(jarFile);
            this.parentFirst = parentFirst;
        }

        String jarName() {
            return jarFile.getName();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = parentFirst.test(name) ? loadParentFirst(name) : loadChildFirst(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private Class<?> loadParentFirst(String name) throws ClassNotFoundException {
            try {
                return super.loadClass(name, false);
            } catch (ClassNotFoundException e) {
                return findClass(name);
            }
        }

        private Class<?> loadChildFirst(String name) throws ClassNotFoundException {
            try {
                return findClass(name);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, false);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            JarEntry entry = jar.getJarEntry(path);
            if (entry == null) {
                throw new ClassNotFoundException(name);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("Cannot read " + name + " from " + jar.getName(), e);
            }
        }

        @Override
        protected URL findResource(String name) {
            JarEntry entry = jar.getJarEntry(name);
            return entry == null ? null : entryUrl(entry);
        }

        @Override
        public Enumeration<URL> findResources(String name) throws IOException {
            List<URL> urls = new ArrayList<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().equals(name)) {
                    urls.add(entryUrl(entry));
                }
            }
            return Collections.enumeration(urls);
        }

        private URL entryUrl(JarEntry entry) {
            try {
                String spec = jarFile.toURI().toURL().toString() + "!/" + entry.getName();
                return URI.create("jar:" + spec).toURL();
            } catch (IllegalArgumentException | MalformedURLException e) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }
}
