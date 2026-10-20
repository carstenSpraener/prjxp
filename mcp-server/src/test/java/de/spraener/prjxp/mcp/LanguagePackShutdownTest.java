package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePackPluginLoader;
import de.spraener.prjxp.common.language.PluginJarBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1 Phase 04: on an orderly shutdown (context close — triggered by the
 * Spring Boot shutdown hook on JVM exit) the plugin loader and all plugin
 * class loaders must be released exactly once.
 */
class LanguagePackShutdownTest {

    @TempDir
    static Path pluginDir;

    @BeforeAll
    static void buildPluginJar() throws IOException {
        PluginJarBuilder.build(pluginDir, "good",
                List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java"),
                "de.spraener.prjxp.pluginfix.good.GoodPackProvider");
    }

    @Test
    void contextShutdownClosesPluginClassLoaders() {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties("prjxp.language-packs.dir=" + pluginDir)
                .run();

        LanguagePackPluginLoader loader = ctx.getBean(LanguagePackPluginLoader.class);
        assertThat(loader.classLoaders()).hasSize(1);

        ctx.close();

        assertThat(loader.classLoaders())
                .as("the loader must be closed on context shutdown")
                .isEmpty();
    }

    @Configuration
    static class TestApp {
    }
}
