package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Tests for {@link EmbeddedArchiveImporter}: name derivation, extraction, merge semantics. */
class EmbeddedArchiveImporterTest {

    @TempDir
    Path tempRoot;

    private Path projectsRoot;
    private HubProperties props;
    private EmbeddedArchiveImporter importer;
    private ObjectMapper objMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        projectsRoot = Files.createDirectories(tempRoot.resolve("projects"));
        props = new HubProperties();
        props.setProjectsRoot(projectsRoot.toString());
        importer = new EmbeddedArchiveImporter(props, new ProjectConfigFileParser(),
                mock(HubProjectRegistry.class), mock(PipelineOrchestrator.class), objMapper);
    }

    @AfterEach
    void tearDown() {
        // nothing to close
    }

    // ------------------------------------------------------------------ name derivation

    @Test
    void deriveProjectNameFromJsonl() {
        assertThat(importer.deriveProjectName("my-project-embedded.jsonl")).isEqualTo("my-project");
    }

    @Test
    void deriveProjectNameFromGz() {
        assertThat(importer.deriveProjectName("my-project-embedded.gz")).isEqualTo("my-project");
    }

    @Test
    void deriveProjectNameFromTgz() {
        assertThat(importer.deriveProjectName("my-project-embedded.tgz")).isEqualTo("my-project");
    }

    @Test
    void deriveProjectNameFromZip() {
        assertThat(importer.deriveProjectName("my-project-embedded.zip")).isEqualTo("my-project");
    }

    @Test
    void deriveProjectNameCaseInsensitive() {
        assertThat(importer.deriveProjectName("My-Project-EMBEDDED.JSONL")).isEqualTo("My-Project");
        assertThat(importer.deriveProjectName("X-Embedded.GZ")).isEqualTo("X");
    }

    @Test
    void deriveProjectNameRejectsMissingSuffix() {
        assertThatThrownBy(() -> importer.deriveProjectName("my-project.jsonl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-embedded");
    }

    @Test
    void deriveProjectNameRejectsEmptyRemainder() {
        assertThatThrownBy(() -> importer.deriveProjectName("embedded.jsonl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-embedded");
    }

    @Test
    void deriveProjectNameRejectsNoExtension() {
        assertThatThrownBy(() -> importer.deriveProjectName("my-project-embedded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-embedded");
    }

    // ------------------------------------------------------------------ round-trip: plain jsonl

    @Test
    void processPlainJsonlCreatesProjectAndEnqueues() throws Exception {
        Path jsonl = tempRoot.resolve("alpha-embedded.jsonl");
        writeValidJsonl(jsonl);

        var result = importer.process(jsonl);

        assertThat(result.name()).isEqualTo("alpha");
        assertThat(projectsRoot.resolve("alpha")).exists();
        assertThat(projectsRoot.resolve("alpha/alpha-embedded.jsonl")).exists();
        assertThat(projectsRoot.resolve("alpha/.ready")).exists();
        // prjxp.yaml should have embeddingsFile set
        assertThat(Files.readString(projectsRoot.resolve("alpha/prjxp.yaml")))
                .contains("embeddingsFile: alpha-embedded.jsonl");
    }

    // ------------------------------------------------------------------ round-trip: gz (plain gzip)

    @Test
    void processPlainGzCreatesProject() throws Exception {
        Path gz = tempRoot.resolve("beta-embedded.gz");
        writeGzippedJsonl(gz);

        var result = importer.process(gz);

        assertThat(result.name()).isEqualTo("beta");
        assertThat(projectsRoot.resolve("beta/beta-embedded.jsonl")).exists();
        assertThat(projectsRoot.resolve("beta/.ready")).exists();
    }

    // ------------------------------------------------------------------ round-trip: tgz (single-entry tar.gz)

    @Test
    void processTgzWithSingleEntryCreatesProject() throws Exception {
        Path tgz = tempRoot.resolve("gamma-embedded.tgz");
        writeTgzWithSingleJsonl(tgz, "my-data.jsonl");

        var result = importer.process(tgz);

        assertThat(result.name()).isEqualTo("gamma");
        assertThat(projectsRoot.resolve("gamma/my-data.jsonl")).exists();
        assertThat(Files.readString(projectsRoot.resolve("gamma/prjxp.yaml")))
                .contains("embeddingsFile: my-data.jsonl");
    }

    // ------------------------------------------------------------------ round-trip: zip (single-entry)

    @Test
    void processZipWithSingleEntryCreatesProject() throws Exception {
        Path zip = tempRoot.resolve("delta-embedded.zip");
        writeZipWithSingleJsonl(zip, "vectors.jsonl");

        var result = importer.process(zip);

        assertThat(result.name()).isEqualTo("delta");
        assertThat(projectsRoot.resolve("delta/vectors.jsonl")).exists();
        assertThat(Files.readString(projectsRoot.resolve("delta/prjxp.yaml")))
                .contains("embeddingsFile: vectors.jsonl");
    }

    // ------------------------------------------------------------------ merge semantics (existing project)

    @Test
    void processIntoExistingProjectMergesNotOverwrites() throws Exception {
        // Create existing project with source files and yaml
        Path existingDir = Files.createDirectories(projectsRoot.resolve("existing"));
        Files.createDirectories(existingDir.resolve("src"));
        Files.writeString(existingDir.resolve("src/Main.java"), "class Main {}");
        Files.writeString(existingDir.resolve("prjxp.yaml"),
                "name: existing\nrootDir: .\nchunoWhiteList: go,rs\n");

        Path jsonl = tempRoot.resolve("existing-embedded.jsonl");
        writeValidJsonl(jsonl);

        importer.process(jsonl);

        // Source file preserved
        assertThat(existingDir.resolve("src/Main.java")).hasContent("class Main {}");
        // YAML updated with embeddingsFile, other fields preserved
        String yaml = Files.readString(existingDir.resolve("prjxp.yaml"));
        assertThat(yaml).contains("embeddingsFile: existing-embedded.jsonl");
        assertThat(yaml).contains("chunoWhiteList: go,rs");
    }

    // ------------------------------------------------------------------ validation errors

    @Test
    void validateRejectsBadName() throws Exception {
        Path file = tempRoot.resolve("bad.jsonl");
        Files.writeString(file, "{}\n");

        assertThatThrownBy(() -> importer.validate(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-embedded");
    }

    @Test
    void validateRejectsInvalidFirstLine() throws Exception {
        Path jsonl = tempRoot.resolve("bad-embedded.jsonl");
        Files.writeString(jsonl, "not valid json\n");

        assertThatThrownBy(() -> importer.validate(jsonl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsNullVector() throws Exception {
        Path jsonl = tempRoot.resolve("novector-embedded.jsonl");
        // Valid JSON but no vector field
        Files.writeString(jsonl, "{\"id\":\"c1\",\"content\":\"test\"}\n");

        assertThatThrownBy(() -> importer.validate(jsonl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsTgzWithMultipleEntries() throws Exception {
        Path tgz = tempRoot.resolve("multi-embedded.tgz");
        writeTgzWithMultipleEntries(tgz);

        assertThatThrownBy(() -> importer.validate(tgz))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsZipWithMultipleEntries() throws Exception {
        Path zip = tempRoot.resolve("multi-embedded.zip");
        writeZipWithMultipleEntries(zip);

        assertThatThrownBy(() -> importer.validate(zip))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsTgzWithNonJsonlEntry() throws Exception {
        Path tgz = tempRoot.resolve("notjsonl-embedded.tgz");
        writeTgzWithNonJsonlEntry(tgz);

        assertThatThrownBy(() -> importer.validate(tgz))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ security: path traversal rejection

    @Test
    void validateRejectsTgzWithAbsolutePath() throws Exception {
        Path tgz = tempRoot.resolve("abs-embedded.tgz");
        writeTgzWithAbsolutePath(tgz);

        assertThatThrownBy(() -> importer.validate(tgz))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsTgzWithDotdotSegment() throws Exception {
        Path tgz = tempRoot.resolve("dotdot-embedded.tgz");
        writeTgzWithDotdot(tgz);

        assertThatThrownBy(() -> importer.validate(tgz))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ archive size limit

    @Test
    void validateRejectsOversizedArchive() throws Exception {
        props.setMaxTarBytes(10); // tiny limit for test
        Path jsonl = tempRoot.resolve("big-embedded.jsonl");
        writeValidJsonl(jsonl); // will be > 10 bytes

        assertThatThrownBy(() -> importer.validate(jsonl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers: create test files

    private String makeEmbeddedChunkRecordJson() throws JsonProcessingException {
        EmbeddedChunkRecord rec = new EmbeddedChunkRecord(
                "c1", "text/plain", "/test.java", null, 0, 1,
                "1", "10", 50, 0, Map.of(), "class Test {}",
                new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f}, null);
        return objMapper.writeValueAsString(rec);
    }

    private void writeValidJsonl(Path path) throws IOException {
        String line = makeEmbeddedChunkRecordJson();
        Files.writeString(path, line + "\n");
    }

    private void writeGzippedJsonl(Path path) throws IOException {
        String line = makeEmbeddedChunkRecordJson();
        byte[] content = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(out)) {
                gzOut.write(content);
            }
        }
    }

    private void writeTgzWithSingleJsonl(Path path, String innerName) throws IOException {
        String line = makeEmbeddedChunkRecordJson();
        byte[] content = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOut = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(fileOut)) {
                try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzOut)) {
                    TarArchiveEntry entry = new TarArchiveEntry(innerName);
                    entry.setSize(content.length);
                    tarOut.putArchiveEntry(entry);
                    tarOut.write(content);
                    tarOut.closeArchiveEntry();
                }
            }
        }
    }

    private void writeZipWithSingleJsonl(Path path, String innerName) throws IOException {
        String line = makeEmbeddedChunkRecordJson();
        byte[] content = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(innerName);
            zipOut.putNextEntry(entry);
            zipOut.write(content);
            zipOut.closeEntry();
        }
    }

    private void writeTgzWithMultipleEntries(Path path) throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOut = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(fileOut)) {
                try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzOut)) {
                    for (String name : List.of("a.jsonl", "b.jsonl")) {
                        TarArchiveEntry entry = new TarArchiveEntry(name);
                        entry.setSize(content.length);
                        tarOut.putArchiveEntry(entry);
                        tarOut.write(content);
                        tarOut.closeArchiveEntry();
                    }
                }
            }
        }
    }

    private void writeZipWithMultipleEntries(Path path) throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        try (java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : List.of("a.jsonl", "b.jsonl")) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
                zipOut.putNextEntry(entry);
                zipOut.write(content);
                zipOut.closeEntry();
            }
        }
    }

    private void writeTgzWithNonJsonlEntry(Path path) throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOut = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(fileOut)) {
                try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzOut)) {
                    TarArchiveEntry entry = new TarArchiveEntry("data.txt");
                    entry.setSize(content.length);
                    tarOut.putArchiveEntry(entry);
                    tarOut.write(content);
                    tarOut.closeArchiveEntry();
                }
            }
        }
    }

    private void writeTgzWithAbsolutePath(Path path) throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOut = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(fileOut)) {
                try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzOut)) {
                    TarArchiveEntry entry = new TarArchiveEntry("/etc/passwd");
                    entry.setSize(content.length);
                    tarOut.putArchiveEntry(entry);
                    tarOut.write(content);
                    tarOut.closeArchiveEntry();
                }
            }
        }
    }

    private void writeTgzWithDotdot(Path path) throws IOException {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOut = Files.newOutputStream(path)) {
            try (GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(fileOut)) {
                try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzOut)) {
                    TarArchiveEntry entry = new TarArchiveEntry("foo/../../bar.jsonl");
                    entry.setSize(content.length);
                    tarOut.putArchiveEntry(entry);
                    tarOut.write(content);
                    tarOut.closeArchiveEntry();
                }
            }
        }
    }
}
