package de.spraener.prjxp.common.language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;

/**
 * Bootstraps external {@link LanguagePack}s before Bean creation.
 *
 * <p>Registered via {@code META-INF/spring.factories}, so every Spring Boot
 * application that ships prjxp-common (mcp-server, chunk-norris, …) picks it
 * up automatically. The plugin directory is configurable via the
 * {@value #PROPERTY_DIR} property (or the {@code PRJXP_LANGUAGE_PACKS_DIR}
 * environment variable, via relaxed binding); the default is
 * {@value #DEFAULT_DIR}, relative to the working directory.
 *
 * <p>The loader is registered as the singleton {@value #BEAN_NAME}; Spring
 * closes it (and its plugin class loaders) on context shutdown. A missing or
 * empty directory is a no-op: the loader is still registered, with no packs.
 */
public class LanguagePackBootstrapInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** Property for the plugin directory (environment variable: {@code PRJXP_LANGUAGE_PACKS_DIR}). */
    public static final String PROPERTY_DIR = "prjxp.language-packs.dir";
    /** Default plugin directory, relative to the working directory. */
    public static final String DEFAULT_DIR = "./language-packs";
    /** Bean name of the registered {@link LanguagePackPluginLoader}. */
    public static final String BEAN_NAME = "languagePackPluginLoader";

    private static final Logger log = LoggerFactory.getLogger(LanguagePackBootstrapInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String dir = context.getEnvironment().getProperty(PROPERTY_DIR, DEFAULT_DIR);
        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(new File(dir));
        loader.loadAll();
        // The context's BeanFactory is not accessible before the first refresh,
        // so the singleton is registered via a BeanFactoryPostProcessor — which
        // runs during refresh, before any Bean is instantiated.
        context.addBeanFactoryPostProcessor(beanFactory ->
                beanFactory.registerSingleton(BEAN_NAME, loader));
        if (loader.loadedPacks().isEmpty()) {
            log.debug("Language pack bootstrap: no external LanguagePacks in {}", dir);
        } else {
            log.info("Language pack bootstrap: {} external LanguagePack(s) from {}",
                    loader.loadedPacks().size(), dir);
        }
    }
}
