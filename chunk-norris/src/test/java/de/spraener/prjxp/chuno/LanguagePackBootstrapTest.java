package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackPluginLoader;
import de.spraener.prjxp.common.language.PluginJarBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1 Phase 02 (batch path): the spring.factories-registered
 * {@code LanguagePackBootstrapInitializer} must load plugin JARs before Bean
 * creation in chunk-norris. The minimal {@code TestConfig} context still goes
 * through {@code SpringApplication}, so the initializer applies exactly as in
 * production.
 */
@SpringBootTest(classes = LanguagePackBootstrapTest.TestConfig.class)
class LanguagePackBootstrapTest {

    @TempDir
    static Path pluginDir;

    @BeforeAll
    static void buildPluginJar() throws IOException {
        PluginJarBuilder.build(pluginDir, "good",
                List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java"),
                "de.spraener.prjxp.pluginfix.good.GoodPackProvider");
    }

    @DynamicPropertySource
    static void pluginDirProperty(DynamicPropertyRegistry registry) {
        registry.add("prjxp.language-packs.dir", () -> pluginDir.toString());
    }

    @Autowired
    LanguagePackPluginLoader loader;

    @Autowired
    ApplicationContext context;

    @Test
    void pluginPackIsAvailableWhenBeansAreCreated() {
        assertThat(loader.loadedPacks()).hasSize(1);
        assertThat(loader.loadedPacks().get(0).language()).isEqualTo("goodlang");
    }

    @Test
    void pluginLoadingHappensBeforeBeanCreation() {
        assertThat(context.getBean("packCountAtBeanCreation", String.class)).isEqualTo("1");
    }

    @Test
    void pluginPackIsNotManagedBySpring() {
        assertThat(context.getBeansOfType(LanguagePack.class).values())
                .as("plugin packs live in the plugin class loader, not in the Spring context")
                .noneMatch(pack -> pack.getClass().getName().contains("pluginfix"));
    }

    @Configuration
    static class TestConfig {

        @Bean
        String packCountAtBeanCreation(LanguagePackPluginLoader loader) {
            return String.valueOf(loader.loadedPacks().size());
        }
    }
}
