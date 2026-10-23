package de.spraener.prjxp.lpgdscript;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import static org.assertj.core.api.Assertions.assertThat;

class PluginJarTest {
    private static final File JAR = new File("build/libs/lp-gdscript-plugin.jar");

    @Test void fatJarExists() { assertThat(JAR).exists().isFile(); }

    @Test void containsSpiFile() throws Exception {
        try (JarFile jar = new JarFile(JAR)) {
            assertThat(jar.getEntry("META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider")).isNotNull();
        }
    }

    @Test void containsPackClasses() throws Exception {
        try (JarFile jar = new JarFile(JAR)) {
            for (String c : List.of("GdscriptLanguagePack", "GdscriptLanguagePackProvider",
                    "GdscriptRetriever", "GdscriptFileViewProvider", "GdscriptCodeChunker", "GdscriptCodeSection")) {
                assertThat(jar.getEntry("de/spraener/prjxp/lpgdscript/" + c + ".class"))
                    .as(c).isNotNull();
            }
        }
    }

    @Test void excludesHostApiAndSlf4j() throws Exception {
        try (JarFile jar = new JarFile(JAR)) {
            List<String> violations = new ArrayList<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("de/spraener/prjxp/common/") || name.startsWith("org/slf4j/")) violations.add(name);
            }
            assertThat(violations).isEmpty();
        }
    }
}
