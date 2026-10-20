package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1 Phase 04: two plugins bundling incompatible versions of the same
 * private library must both load — each sees its own copy (child-first),
 * while the shared host API (prjxp-common) stays on the host's class identity
 * (parent-first).
 */
class PluginIsolationTest {

    @TempDir
    Path pluginDir;

    @Test
    void twoPluginsWithIncompatiblePrivateLibraryVersions_bothLoadIsolated() throws IOException {
        PluginJarBuilder.build(pluginDir, "privlib-a",
                List.of("com/example/lib/Lib.java",
                        "de/spraener/prjxp/pluginfix/privliba/PrivLibAPackProvider.java"),
                "de.spraener.prjxp.pluginfix.privliba.PrivLibAPackProvider");
        PluginJarBuilder.build(pluginDir, "privlib-b",
                List.of("com/example/lib/Lib.java",
                        "de/spraener/prjxp/pluginfix/privlibb/PrivLibBPackProvider.java"),
                "de.spraener.prjxp.pluginfix.privlibb.PrivLibBPackProvider");

        LanguagePackPluginLoader loader = new LanguagePackPluginLoader(pluginDir.toFile());
        List<LanguagePack> packs = loader.loadAll();

        assertThat(packs).hasSize(2);

        File sample = pluginDir.resolve("sample.txt").toFile();
        Files.writeString(sample.toPath(), "whatever");

        Map<String, String> contentByLanguage = new HashMap<>();
        for (LanguagePack pack : packs) {
            PxChunker chunker = pack.findPxChunkers(sample).findFirst().orElseThrow();
            PxChunk chunk = chunker.chunk(sample).findFirst().orElseThrow();
            contentByLanguage.put(pack.language(), chunk.getContent());
        }

        // each plugin saw its own private library version
        assertThat(contentByLanguage)
                .containsEntry("privlib-a", "privlib-a: v1")
                .containsEntry("privlib-b", "privlib-b: v2");

        // the shared host API stays on the host's class identity
        for (LanguagePack pack : packs) {
            assertThat(pack.getClass().getInterfaces()[0].getClassLoader())
                    .as("host API class identity for %s", pack.getClass().getName())
                    .isSameAs(LanguagePack.class.getClassLoader());
        }
    }
}
