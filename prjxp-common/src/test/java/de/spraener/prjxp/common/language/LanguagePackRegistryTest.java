package de.spraener.prjxp.common.language;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link LanguagePackRegistry}: access, the
 * plugin-wins conflict rule, split halves, and the Spring wiring.
 */
class LanguagePackRegistryTest {

    private static final List<String> GOOD_SOURCES =
            List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java");
    private static final String GOOD_PROVIDER = "de.spraener.prjxp.pluginfix.good.GoodPackProvider";

    @TempDir
    static Path pluginDir;

    private Logger registryLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        registryLogger = (Logger) org.slf4j.LoggerFactory.getLogger(LanguagePackRegistry.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        registryLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        registryLogger.detachAppender(logAppender);
    }

    /* --- Test 1: only built-in packs --- */
    @Test
    void builtInPacksOnly_keptInGivenOrder() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("java", "text/x-java"),
                        new BuiltInPack("typescript", "text/x-typescript")),
                List.of());

        assertThat(registry.all()).extracting(LanguagePack::language)
                .containsExactly("java", "typescript");
        assertThat(registry.mimeByLanguage())
                .containsEntry("java", "text/x-java")
                .containsEntry("typescript", "text/x-typescript");
        assertThat(logAppender.list).isEmpty();
    }

    /* --- Test 2: plugin for a new language --- */
    @Test
    void pluginForNewLanguage_appendedAfterBuiltIns() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("java", "text/x-java")),
                List.of(new PluginPack("csharp", "text/x-csharp")));

        assertThat(registry.all()).extracting(LanguagePack::language)
                .containsExactly("java", "csharp");
        assertThat(registry.mimeByLanguage()).containsEntry("csharp", "text/x-csharp");
        assertThat(logAppender.list).isEmpty();   // no conflict, no warning
    }

    /* --- Test 3: plugin overrides built-in with same language() --- */
    @Test
    void pluginOverridesBuiltIn_sameLanguage() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("java", "text/x-java")),
                List.of(new PluginPack("java", "text/x-java-plugin")));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0)).isInstanceOf(PluginPack.class);
        assertThat(registry.mimeByLanguage()).containsEntry("java", "text/x-java-plugin");

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains(BuiltInPack.class.getName())
                        && e.getFormattedMessage().contains(PluginPack.class.getName())
                        && e.getFormattedMessage().contains("java"));
    }

    /* --- Test 4: built-in split halves (chunker half + retriever half) coexist --- */
    @Test
    void splitHalvesCoexist_withoutPlugin() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("java", "text/x-java"),
                        new BuiltInPack("java", "text/x-java")),
                List.of());

        assertThat(registry.all()).hasSize(2);
        assertThat(logAppender.list).isEmpty();
    }

    /* --- Test 5: plugin overrides a language with split halves --- */
    @Test
    void pluginOverridesSplitHalves_bothHalvesReplaced() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("java", "text/x-java"),
                        new BuiltInPack("java", "text/x-java")),
                List.of(new PluginPack("java", "text/x-java-plugin")));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0)).isInstanceOf(PluginPack.class);

        long warnings = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains(BuiltInPack.class.getName()))
                .count();
        assertThat(warnings).isEqualTo(2);   // one per overridden half
    }

    /* --- Test 6: multiple plugin packs for one language: first wins --- */
    @Test
    void multiplePluginPacks_sameLanguage_firstWins() {
        LanguagePack first = new PluginPack("java", "text/x-java-first");
        LanguagePack second = new PluginPack("java", "text/x-java-second");

        LanguagePackRegistry registry = new LanguagePackRegistry(List.of(), List.of(first, second));

        assertThat(registry.all()).containsExactly(first);
        assertThat(registry.mimeByLanguage()).containsEntry("java", "text/x-java-first");
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains(second.getClass().getName()));
    }

    /* --- Test 7: MIME keys are normalized to lower case --- */
    @Test
    void mimeByLanguage_normalizesLanguageCase() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new BuiltInPack("Java", "text/x-java")),
                List.of());

        assertThat(registry.mimeByLanguage()).containsKey("java");
    }

    /* --- Test 8: Spring wiring — built-in beans + loader singleton --- */
    @Test
    void springWiring_resolvesBuiltInBeansAndPluginLoader() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(WiringConfig.class);
        ctx.refresh();

        LanguagePackRegistry registry = ctx.getBean(LanguagePackRegistry.class);
        assertThat(registry.all()).extracting(LanguagePack::language)
                .containsExactly("java", "goodlang");
        assertThat(registry.mimeByLanguage()).containsEntry("goodlang", "text/x-goodlang-code");

        ctx.close();
    }

    @Configuration
    static class WiringConfig {

        @Bean
        LanguagePackRegistry languagePackRegistry(ObjectProvider<LanguagePack> builtInPacks,
                                                  ObjectProvider<LanguagePackPluginLoader> loader) {
            return new LanguagePackRegistry(builtInPacks, loader);
        }

        @Bean
        LanguagePackPluginLoader languagePackPluginLoader() {
            LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());
            loader.loadAll();
            return loader;
        }

        @Bean
        LanguagePack javaPack() {
            return new BuiltInPack("java", "text/x-java");
        }
    }

    // --- Test doubles ---

    private static class BuiltInPack implements LanguagePack {
        private final String language;
        private final String mimeType;

        BuiltInPack(String language, String mimeType) {
            this.language = language;
            this.mimeType = mimeType;
        }

        @Override public String language() { return language; }
        @Override public String mimeType() { return mimeType; }
    }

    private static class PluginPack implements LanguagePack {
        private final String language;
        private final String mimeType;

        PluginPack(String language, String mimeType) {
            this.language = language;
            this.mimeType = mimeType;
        }

        @Override public String language() { return language; }
        @Override public String mimeType() { return mimeType; }
    }
}
