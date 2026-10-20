package de.spraener.prjxp.common.language;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LanguagePackPluginLoaderTest {

    private static final List<String> GOOD_SOURCES =
            List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java");
    private static final String GOOD_PROVIDER = "de.spraener.prjxp.pluginfix.good.GoodPackProvider";

    @TempDir
    Path pluginDir;

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logger = (Logger) org.slf4j.LoggerFactory.getLogger(LanguagePackPluginLoader.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
    }

    @Test
    void missingDirectoryReturnsEmptyResult() {
        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(new File(pluginDir.toFile(), "does-not-exist"));

        assertThat(loader.loadAll()).isEmpty();
    }

    @Test
    void emptyDirectoryReturnsEmptyResult() {
        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());

        assertThat(loader.loadAll()).isEmpty();
    }

    @Test
    void loadsProvidersFromMultipleJars() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        PluginJarBuilder.build(pluginDir, "second",
                List.of("de/spraener/prjxp/pluginfix/second/SecondPackProvider.java"),
                "de.spraener.prjxp.pluginfix.second.SecondPackProvider");

        List<LanguagePack> packs = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll();

        assertThat(packs).hasSize(2);
        assertThat(packs).extracting(LanguagePack::language).containsExactlyInAnyOrder("goodlang", "secondlang");
    }

    @Test
    void brokenProviderDoesNotBlockOtherJars() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        PluginJarBuilder.build(pluginDir, "broken",
                List.of("de/spraener/prjxp/pluginfix/broken/BrokenPackProvider.java"),
                "de.spraener.prjxp.pluginfix.broken.BrokenPackProvider");

        List<LanguagePack> packs = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll();

        assertThat(packs).hasSize(1);
        assertThat(packs.get(0).language()).isEqualTo("goodlang");
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("broken.jar"));
    }

    @Test
    void jarWithoutSpiFileLoadsNothingWithoutError() throws IOException {
        PluginJarBuilder.build(pluginDir, "second",
                List.of("de/spraener/prjxp/pluginfix/second/SecondPackProvider.java"));

        List<LanguagePack> packs = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll();

        assertThat(packs).isEmpty();
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    void corruptJarFileIsSkipped() throws IOException {
        Files.writeString(pluginDir.resolve("corrupt.jar"), "this is not a zip file");

        List<LanguagePack> packs = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll();

        assertThat(packs).isEmpty();
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("corrupt.jar"));
    }

    @Test
    void loadedPackUsesHostLanguagePackIdentity() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);

        LanguagePack pack = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll().get(0);

        assertThat(pack).isInstanceOf(LanguagePack.class);
        assertThat(pack.getClass().getInterfaces()).contains(LanguagePack.class);
        assertThat(pack.getClass().getInterfaces()[0].getClassLoader())
                .isSameAs(LanguagePack.class.getClassLoader());
    }

    @Test
    void pxChunkRoundtripThroughClassLoaderBoundary() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);
        LanguagePack pack = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll().get(0);

        File sample = pluginDir.resolve("sample.good").toFile();
        Files.writeString(sample.toPath(), "whatever");

        PxChunker chunker = pack.findPxChunkers(sample).findFirst().orElseThrow();
        List<PxChunk> chunks = chunker.chunk(sample).toList();

        assertThat(chunks).hasSize(1);
        PxChunk chunk = chunks.get(0);
        assertThat(chunk.getClass()).isSameAs(PxChunk.class);
        // combine() sorts its argument — Stream.toList() is unmodifiable, so copy it
        PxChunk combined = PxChunk.combine(new java.util.ArrayList<>(chunks));
        assertThat(combined).isNotNull();
        assertThat(combined.getContent()).isEqualTo("hello from the plugin");
    }

    @Test
    void identityBreakIsDetectedAtLoadTime() throws IOException {
        PluginJarBuilder.build(pluginDir, "identity-break",
                List.of("de/spraener/prjxp/common/language/LanguagePack.java",
                        "de/spraener/prjxp/pluginfix/brokenidentity/BrokenIdentityProvider.java"),
                "de.spraener.prjxp.pluginfix.brokenidentity.BrokenIdentityProvider");

        // Simulated broken delegation: child-first for everything
        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile(), name -> false);
        List<LanguagePack> packs = loader.loadAll();

        assertThat(packs).isEmpty();
        // The break can surface as a LinkageError (loader constraint violation) during
        // ServiceLoader discovery, or as a failed instanceof check — both must be
        // rejected at load time with an ERROR diagnostic naming the JAR.
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("identity-break.jar"));
    }

    @Test
    void contractViolationIsDetectedAtLoadTime() throws IOException {
        PluginJarBuilder.build(pluginDir, "contract-violation",
                List.of("de/spraener/prjxp/pluginfix/contractviolation/ContractViolatingProvider.java"),
                "de.spraener.prjxp.pluginfix.contractviolation.ContractViolatingProvider");

        List<LanguagePack> packs = new LanguagePackPluginLoader(pluginDir.toFile()).loadAll();

        assertThat(packs).isEmpty();
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("contract validation failed"));
    }

    @Test
    void loadAllIsIdempotent() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);

        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());
        List<LanguagePack> first = loader.loadAll();
        List<LanguagePack> second = loader.loadAll();

        assertThat(second).isSameAs(first);
        assertThat(loader.classLoaders()).hasSize(1);
    }

    @Test
    void closeIsIdempotent() throws IOException {
        PluginJarBuilder.build(pluginDir, "good", GOOD_SOURCES, GOOD_PROVIDER);

        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());
        loader.loadAll();
        assertThat(loader.classLoaders()).hasSize(1);

        loader.close();
        assertThat(loader.classLoaders()).isEmpty();
        assertThatCode(loader::close).doesNotThrowAnyException();
    }
}
