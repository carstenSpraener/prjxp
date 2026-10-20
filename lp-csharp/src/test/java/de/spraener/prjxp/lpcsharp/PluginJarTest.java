package de.spraener.prjxp.lpcsharp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the plugin fat-JAR contract: it must contain the SPI provider,
 * the SPI service file and the ANTLR runtime — but none of the host API
 * (de.spraener.prjxp.common.**), slf4j, Spring or LangChain4j.
 */
class PluginJarTest {

    private static final String SPI_FILE =
            "META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider";

    private static Path pluginJar() throws IOException {
        Path jar = Path.of("build", "libs", "lp-csharp-plugin.jar");
        assertThat(jar).exists();
        return jar;
    }

    private static List<String> entries(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                names.add(e.nextElement().getName());
            }
        }
        return names;
    }

    @Test
    void jarContainsSpiProviderAndServiceFile() throws IOException {
        List<String> names = entries(pluginJar());
        assertThat(names).contains(
                "de/spraener/prjxp/lpcsharp/CSharpLanguagePackProvider.class",
                "de/spraener/prjxp/lpcsharp/CSharpLanguagePack.class",
                "de/spraener/prjxp/lpcsharp/CSharpCodeChunker.class",
                "de/spraener/prjxp/lpcsharp/CSharpRetriever.class",
                SPI_FILE);
    }

    @Test
    void jarBundlesAntlrRuntime() throws IOException {
        List<String> names = entries(pluginJar());
        assertThat(names).anySatisfy(n -> assertThat(n).startsWith("org/antlr/v4/runtime/"));
    }

    @Test
    void jarExcludesHostApiAndLogging() throws IOException {
        List<String> names = entries(pluginJar());
        assertThat(names).noneMatch(n -> n.startsWith("de/spraener/prjxp/common/"));
        assertThat(names).noneMatch(n -> n.startsWith("org/slf4j/"));
        assertThat(names).noneMatch(n -> n.startsWith("org/springframework/"));
        assertThat(names).noneMatch(n -> n.startsWith("dev/langchain/"));
    }

    @Test
    void spiFileNamesTheProvider() throws IOException {
        try (JarFile jf = new JarFile(pluginJar().toFile())) {
            JarEntry entry = jf.getJarEntry(SPI_FILE);
            assertThat(entry).isNotNull();
            String content = new String(jf.getInputStream(entry).readAllBytes());
            assertThat(content).contains("de.spraener.prjxp.lpcsharp.CSharpLanguagePackProvider");
        }
    }
}
