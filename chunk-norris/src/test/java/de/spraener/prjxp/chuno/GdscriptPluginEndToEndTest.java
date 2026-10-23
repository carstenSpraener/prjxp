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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 04 (Batch path): end-to-end test with the REAL lp-gdscript plugin JAR.
 *
 * <p>The full chunk-norris context (real {@link ChunkProcess}, real
 * {@link ChunkerFactory} → {@link LanguagePackBroker} → plugin packs) chunks a
 * GDScript fixture project; the plugin chunker (loaded via its own class loader
 * from the staged plugin JAR) must produce GDScript chunks in the JSONL output.
 *
 * <p>The plugin JAR is staged by the {@code stageLpGdscriptPlugin} Gradle task
 * (see build.gradle) and passed in via the {@code prjxp.gdscript.e2e.pluginDir}
 * system property; only this test points {@code prjxp.language-packs.dir} at it.
 */
// "test" profile: disables the chunoRun CLI runner (it would chunk the working directory)
@SpringBootTest
@ActiveProfiles("test")
class GdscriptPluginEndToEndTest {

    private static final String GDSCRIPT_SOURCE = """
            class_name Player
            extends CharacterBody2D
            ## A player that moves and shoots.

            @export var speed = 300
            var health = 100
            signal died

            ## Moves the player in the given direction.
            func move(dir):
            	position += dir * speed

            ## Fires a projectile toward the target.
            func shoot(target):
            	print("shoot at ", target)

            class AI:
            	var brain = 1
            """;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void pluginDirProperty(DynamicPropertyRegistry registry) {
        registry.add("prjxp.language-packs.dir",
                () -> System.getProperty("prjxp.gdscript.e2e.pluginDir", "build/language-packs"));
    }

    @Autowired
    ChunkProcess chunkProcess;

    @Autowired
    LanguagePackRegistry languagePackRegistry;

    @Autowired
    LanguagePackBroker languagePackBroker;

    @Test
    void gdscriptPluginPackIsLoadedAndRegistered() {
        assertThat(languagePackRegistry.mimeByLanguage())
                .as("the plugin pack must be visible in the host registry")
                .containsEntry("gdscript", "text/x-gdscript-code");
        assertThat(languagePackBroker.findPxChunkers(new File("player.gd")).count())
                .as("the broker must resolve the plugin chunker for .gd files")
                .isEqualTo(1);
    }

    @Test
    void gdscriptProjectIsChunkedToJsonl() throws Exception {
        // Fixture project: GDScript source + a whitelist that admits .gd files
        Path projectDir = Files.createDirectories(tempDir.resolve("gdscript-demo"));
        Files.writeString(projectDir.resolve("player.gd"), GDSCRIPT_SOURCE);
        Path jsonlFile = tempDir.resolve("gdscript-demo-chunks.jsonl");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("gdscript-demo");
        pd.setRootDir(projectDir.toString());
        pd.setJsonlFile(jsonlFile.toString());
        pd.setChunoWhiteList("gd");

        chunkProcess.executeForProject(pd);

        assertThat(Files.exists(jsonlFile)).as("chunk JSONL").isTrue();
        String jsonl = Files.readString(jsonlFile);
        assertThat(jsonl).contains("text/x-gdscript-code");
        assertThat(jsonl).contains("gdscript_code_section");
        assertThat(jsonl).contains("Player");
        assertThat(jsonl).contains("header");
        assertThat(jsonl).contains("symbol_fqn");
    }
}
