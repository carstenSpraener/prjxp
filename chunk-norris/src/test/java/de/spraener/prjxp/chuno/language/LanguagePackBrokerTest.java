package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the broker: it must serve chunkers from the shared
 * {@link LanguagePackRegistry} (built-in packs + plugin packs) and apply
 * the plugin-wins conflict rule.
 */
class LanguagePackBrokerTest {

    @Test
    void findPxChunkers_returnsPluginChunkerForMatchingFile() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(), List.of(new ExtensionPack("goodlang", ".good")));
        LanguagePackBroker broker = new LanguagePackBroker(registry);

        assertThat(broker.findPxChunkers(new File("test.good")).count()).isEqualTo(1);
        assertThat(broker.findPxChunkers(new File("test.java")).count()).isZero();
    }

    @Test
    void findPxChunkers_returnsBuiltInChunker() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new ExtensionPack("java", ".java")), List.of());
        LanguagePackBroker broker = new LanguagePackBroker(registry);

        assertThat(broker.findPxChunkers(new File("A.java")).count()).isEqualTo(1);
    }

    @Test
    void pluginOverride_excludesBuiltInChunker() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new ExtensionPack("java", ".java")),
                List.of(new ExtensionPack("java", ".javaplugin")));
        LanguagePackBroker broker = new LanguagePackBroker(registry);

        assertThat(broker.findPxChunkers(new File("A.java")).count()).isZero();
        assertThat(broker.findPxChunkers(new File("A.javaplugin")).count()).isEqualTo(1);
    }

    @Test
    void listPostWalkChunkers_fromRegistry() {
        LanguagePackRegistry registry = new LanguagePackRegistry(
                List.of(new PostWalkPack("java")), List.of());
        LanguagePackBroker broker = new LanguagePackBroker(registry);

        assertThat(broker.listPostWalkChunker().count()).isEqualTo(1);
    }

    // --- Test doubles ---

    private static class ExtensionPack implements LanguagePack {
        private final String language;
        private final String extension;

        ExtensionPack(String language, String extension) {
            this.language = language;
            this.extension = extension;
        }

        @Override public String language() { return language; }
        @Override public String mimeType() { return "text/x-" + language; }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.<PxChunker>of(new ExtensionChunker(extension)).filter(c -> c.matches(f));
        }
    }

    private static class ExtensionChunker implements PxChunker {
        private final String extension;

        ExtensionChunker(String extension) {
            this.extension = extension;
        }

        @Override
        public Stream<PxChunk> chunk(File f) {
            return Stream.of(PxChunk.create(c -> {
                c.setId("t-1");
                c.setFile(f.getAbsolutePath());
                c.setPart(0);
                c.setTotal(1);
                c.setContent("test");
            }));
        }

        @Override
        public boolean matches(File f) {
            return f.getName().endsWith(extension);
        }
    }

    private static class PostWalkPack implements LanguagePack {
        private final String language;

        PostWalkPack(String language) {
            this.language = language;
        }

        @Override public String language() { return language; }
        @Override public String mimeType() { return "text/x-" + language; }

        @Override
        public Stream<PxChunker> listPostWalkChunkers() {
            return Stream.of(new ExtensionChunker(".java"));
        }
    }
}
