package de.spraener.prjxp.common.language;

/**
 * SPI factory contract for external Language Packs.
 *
 * <p>External packs are shipped as plain JARs in a plugin directory (default
 * {@code ./language-packs}). Each JAR declares one or more providers via
 * {@code META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider};
 * the host instantiates them through {@link #create()} inside an isolating
 * class loader (see {@link LanguagePackPluginLoader}).
 *
 * <p>Providers are self-contained: no arguments, no Spring. Configuration
 * belongs into the plugin JAR's own resources or system properties.
 */
public interface LanguagePackProvider {

    /**
     * Creates a new {@link LanguagePack} instance.
     * Must be cheap and free of side effects — the host calls it once at startup.
     */
    LanguagePack create();
}
