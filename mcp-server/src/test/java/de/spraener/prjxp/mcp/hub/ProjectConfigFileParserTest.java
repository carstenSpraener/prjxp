package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 03: {@link ProjectConfigFileParser} maps {@code <projectDir>/prjxp.yaml} onto a
 * {@link ProjectDefinition}; missing/empty/broken content always falls back to defaults.
 */
class ProjectConfigFileParserTest {

    @TempDir
    Path projectDir;

    private final ProjectConfigFileParser parser = new ProjectConfigFileParser();

    @Test
    void missingConfigFileYieldsAllDefaults() {
        ProjectDefinition def = parser.parse(projectDir, "alpha");

        assertThat(def.getName()).isEqualTo("alpha");
        assertThat(def.getRootDir()).isEqualTo(".");
        assertThat(def.getJsonlFile()).isEqualTo("px-chunks.jsonl");
        assertThat(def.getChunoWhiteList()).isEqualTo("java,ts");
        assertThat(def.getTibedBatchSize()).isEqualTo(32);
    }

    @Test
    void emptyConfigFileYieldsAllDefaults() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "   \n");

        ProjectDefinition def = parser.parse(projectDir, "alpha");

        assertThat(def.getName()).isEqualTo("alpha");
        assertThat(def.getTibedBatchSize()).isEqualTo(32);
    }

    @Test
    void fullConfigFilePicksUpAllValues() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), """
                name: beta-from-yaml
                rootDir: /custom/root
                jsonlFile: custom-chunks.jsonl
                chunoWhiteList: python,go
                tibedBatchSize: 64
                """);

        ProjectDefinition def = parser.parse(projectDir, "beta");

        assertThat(def.getName()).isEqualTo("beta-from-yaml");
        assertThat(def.getRootDir()).isEqualTo("/custom/root");
        assertThat(def.getJsonlFile()).isEqualTo("custom-chunks.jsonl");
        assertThat(def.getChunoWhiteList()).isEqualTo("python,go");
        assertThat(def.getTibedBatchSize()).isEqualTo(64);
    }

    @Test
    void partialConfigFileKeepsDefaultsForMissingKeys() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "tibedBatchSize: 16\n");

        ProjectDefinition def = parser.parse(projectDir, "gamma");

        assertThat(def.getName()).isEqualTo("gamma");
        assertThat(def.getRootDir()).isEqualTo(".");
        assertThat(def.getJsonlFile()).isEqualTo("px-chunks.jsonl");
        assertThat(def.getChunoWhiteList()).isEqualTo("java,ts");
        assertThat(def.getTibedBatchSize()).isEqualTo(16);
    }

    @Test
    void blankValuesFallBackToDefaults() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), """
                name: ""
                rootDir: "   "
                tibedBatchSize: abc
                """);

        ProjectDefinition def = parser.parse(projectDir, "delta");

        assertThat(def.getName()).isEqualTo("delta");
        assertThat(def.getRootDir()).isEqualTo(".");
        assertThat(def.getTibedBatchSize()).isEqualTo(32);
    }

    @Test
    void quotedNumericStringBatchSizeIsParsed() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "tibedBatchSize: \"64\"\n");

        assertThat(parser.parse(projectDir, "delta").getTibedBatchSize()).isEqualTo(64);
    }

    @Test
    void nonMapContentYieldsDefaultsWithoutException() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "just a plain string\n");

        assertThatCode(() -> parser.parse(projectDir, "alpha")).doesNotThrowAnyException();
        ProjectDefinition def = parser.parse(projectDir, "alpha");

        assertThat(def.getName()).isEqualTo("alpha");
    }

    @Test
    void garbageContentYieldsDefaultsWithoutException() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "key: [1, 2\n");   // unclosed flow sequence

        assertThatCode(() -> parser.parse(projectDir, "alpha")).doesNotThrowAnyException();
        ProjectDefinition def = parser.parse(projectDir, "alpha");

        assertThat(def.getName()).isEqualTo("alpha");
        assertThat(def.getTibedBatchSize()).isEqualTo(32);
    }

    // ------------------------------------------------------------------ Phase 06: .yml fallback + markerFile

    @Test
    void ymlExtensionIsAcceptedWhenYamlMissing() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yml"), "tibedBatchSize: 16\n");

        ProjectDefinition def = parser.parse(projectDir, "alpha");

        assertThat(def.getName()).isEqualTo("alpha");
        assertThat(def.getTibedBatchSize()).isEqualTo(16);
    }

    @Test
    void yamlTakesPrecedenceOverYml() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "tibedBatchSize: 16\n");
        Files.writeString(projectDir.resolve("prjxp.yml"), "tibedBatchSize: 64\n");

        assertThat(parser.parse(projectDir, "alpha").getTibedBatchSize()).isEqualTo(16);
    }

    @Test
    void markerFileReturnsExistingMarkerYamlFirst() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yaml"), "tibedBatchSize: 16\n");
        Files.writeString(projectDir.resolve("prjxp.yml"), "tibedBatchSize: 64\n");

        assertThat(parser.markerFile(projectDir)).contains(projectDir.resolve("prjxp.yaml"));
    }

    @Test
    void markerFileFallsBackToYml() throws Exception {
        Files.writeString(projectDir.resolve("prjxp.yml"), "tibedBatchSize: 16\n");

        assertThat(parser.markerFile(projectDir)).contains(projectDir.resolve("prjxp.yml"));
    }

    @Test
    void markerFileEmptyWhenNoMarkerPresent() {
        assertThat(parser.markerFile(projectDir)).isEmpty();
    }
}
