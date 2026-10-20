package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.language.LanguagePackBroker;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-2 Phase 04 (Batch path): end-to-end test with the REAL lp-csharp plugin JAR.
 *
 * <p>The full chunk-norris context (real {@link ChunkProcess}, real
 * {@link ChunkerFactory} → {@link LanguagePackBroker} → plugin packs) chunks a
 * C# fixture project; the plugin chunker (loaded via its own class loader from
 * the staged plugin JAR) must produce C# chunks in the JSONL output.
 *
 * <p>The plugin JAR is staged by the {@code stageLpCsharpPlugin} Gradle task
 * (see build.gradle) and passed in via the {@code prjxp.csharp.e2e.pluginDir}
 * system property; only this test points {@code prjxp.language-packs.dir} at it.
 */
// "test" profile: disables the chunoRun CLI runner (it would chunk the working directory)
@SpringBootTest
@ActiveProfiles("test")
class CSharpPluginEndToEndTest {

    private static final String CSHARP_SOURCE = """
            using System;

            namespace Acme
            {
                /// <summary>
                /// A calculator that tracks how many operations were performed.
                /// </summary>
                public class Calculator
                {
                    private int operationsCount;

                    public Calculator()
                    {
                        operationsCount = 0;
                    }

                    /// <summary>
                    /// Adds two integers and returns the sum.
                    /// </summary>
                    public int Add(int a, int b)
                    {
                        operationsCount++;
                        return a + b;
                    }

                    /// <summary>
                    /// Multiplies two integers and returns the product.
                    /// </summary>
                    public int Multiply(int a, int b)
                    {
                        operationsCount++;
                        return a * b;
                    }
                }
            }
            """;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void pluginDirProperty(DynamicPropertyRegistry registry) {
        registry.add("prjxp.language-packs.dir",
                () -> System.getProperty("prjxp.csharp.e2e.pluginDir", "build/language-packs"));
    }

    @Autowired
    ChunkProcess chunkProcess;

    @Autowired
    LanguagePackRegistry languagePackRegistry;

    @Autowired
    LanguagePackBroker languagePackBroker;

    @Test
    void csharpPluginPackIsLoadedAndRegistered() {
        assertThat(languagePackRegistry.mimeByLanguage())
                .as("the plugin pack must be visible in the host registry")
                .containsEntry("csharp", "text/x-csharp-code");
        assertThat(languagePackBroker.findPxChunkers(new java.io.File("Calculator.cs")).count())
                .as("the broker must resolve the plugin chunker for .cs files")
                .isEqualTo(1);
    }

    @Test
    void csharpProjectIsChunkedToJsonl() throws Exception {
        // Fixture project: C# source + a whitelist that admits .cs files
        Path projectDir = Files.createDirectories(tempDir.resolve("csharp-demo"));
        Files.writeString(projectDir.resolve("Calculator.cs"), CSHARP_SOURCE);
        Path jsonlFile = tempDir.resolve("csharp-demo-chunks.jsonl");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("csharp-demo");
        pd.setRootDir(projectDir.toString());
        pd.setJsonlFile(jsonlFile.toString());
        pd.setChunoWhiteList("cs");

        chunkProcess.executeForProject(pd);

        assertThat(Files.exists(jsonlFile)).as("chunk JSONL").isTrue();
        String jsonl = Files.readString(jsonlFile);
        assertThat(jsonl).contains("text/x-csharp-code");
        assertThat(jsonl).contains("csharp_code_section");
        assertThat(jsonl).contains("Acme.Calculator");
        assertThat(jsonl).contains("typeFrame");
        assertThat(jsonl).contains("symbol_fqn");
    }
}
