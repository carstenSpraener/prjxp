package de.spraener.prjxp.common.language;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the Spring bootstrap: the initializer must resolve the
 * plugin directory from the Environment, load the plugins before Bean
 * creation, and register the loader as a singleton.
 */
class LanguagePackBootstrapInitializerTest {

    private static final List<String> GOOD_SOURCES =
            List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java");
    private static final String GOOD_PROVIDER = "de.spraener.prjxp.pluginfix.good.GoodPackProvider";

    @TempDir
    Path pluginDir;

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
    }

    private AnnotationConfigApplicationContext newContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        if (!properties.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        }
        ctx.setEnvironment(env);
        contexts.add(ctx);
        return ctx;
    }

    private LanguagePackPluginLoader initializeAndRefresh(AnnotationConfigApplicationContext ctx) {
        new LanguagePackBootstrapInitializer().initialize(ctx);
        ctx.refresh();
        return ctx.getBean(LanguagePackBootstrapInitializer.BEAN_NAME, LanguagePackPluginLoader.class);
    }

    @Test
    void defaultDirectoryIsLanguagePacks() {
        LanguagePackPluginLoader loader = initializeAndRefresh(newContext(Map.of()));

        assertThat(loader.pluginDir().getPath()).isEqualTo(LanguagePackBootstrapInitializer.DEFAULT_DIR);
        assertThat(loader.loadedPacks()).isEmpty();
    }

    @Test
    void propertyOverridesDirectory() {
        LanguagePackPluginLoader loader = initializeAndRefresh(
                newContext(Map.of(LanguagePackBootstrapInitializer.PROPERTY_DIR, pluginDir.toString())));

        assertThat(loader.pluginDir().getPath()).isEqualTo(pluginDir.toString());
    }

    @Test
    void environmentVariableOverridesDirectoryViaRelaxedBinding() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "testEnv", Map.of("PRJXP_LANGUAGE_PACKS_DIR", pluginDir.toString())));
        ctx.setEnvironment(env);
        contexts.add(ctx);

        LanguagePackPluginLoader loader = initializeAndRefresh(ctx);

        assertThat(loader.pluginDir().getPath()).isEqualTo(pluginDir.toString());
    }

    @Test
    void missingDirectoryIsNoOp() {
        AnnotationConfigApplicationContext ctx = newContext(Map.of(
                LanguagePackBootstrapInitializer.PROPERTY_DIR,
                new java.io.File(pluginDir.toFile(), "does-not-exist").toString()));

        assertThatCode(() -> initializeAndRefresh(ctx)).doesNotThrowAnyException();
        assertThat(ctx.getBean(LanguagePackBootstrapInitializer.BEAN_NAME, LanguagePackPluginLoader.class)
                .loadedPacks()).isEmpty();
    }

    @Test
    void emptyDirectoryIsNoOp() {
        LanguagePackPluginLoader loader = initializeAndRefresh(
                newContext(Map.of(LanguagePackBootstrapInitializer.PROPERTY_DIR, pluginDir.toString())));

        assertThat(loader.loadedPacks()).isEmpty();
    }

    @Test
    void loadsProvidersFromConfiguredDirectory() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);

        LanguagePackPluginLoader loader = initializeAndRefresh(
                newContext(Map.of(LanguagePackBootstrapInitializer.PROPERTY_DIR, pluginDir.toString())));

        assertThat(loader.loadedPacks()).hasSize(1);
        assertThat(loader.loadedPacks().get(0).language()).isEqualTo("goodlang");
    }

    @Test
    void pluginLoadingHappensBeforeBeanCreation() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        AnnotationConfigApplicationContext ctx = newContext(Map.of(
                LanguagePackBootstrapInitializer.PROPERTY_DIR, pluginDir.toString()));
        new LanguagePackBootstrapInitializer().initialize(ctx);

        ctx.register(OrderProbeConfig.class);
        ctx.refresh();

        assertThat(ctx.getBean("packCountAtBeanCreation", String.class)).isEqualTo("1");
    }

    @Test
    void pluginPacksAreNotSpringBeans() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        AnnotationConfigApplicationContext ctx = newContext(Map.of(
                LanguagePackBootstrapInitializer.PROPERTY_DIR, pluginDir.toString()));
        initializeAndRefresh(ctx);

        assertThat(ctx.getBeansOfType(LanguagePack.class)).isEmpty();
    }

    /** Probes the loader state at Bean-creation time (during context refresh). */
    @Configuration
    static class OrderProbeConfig {

        @Bean
        String packCountAtBeanCreation(LanguagePackPluginLoader loader) {
            return String.valueOf(loader.loadedPacks().size());
        }
    }
}
