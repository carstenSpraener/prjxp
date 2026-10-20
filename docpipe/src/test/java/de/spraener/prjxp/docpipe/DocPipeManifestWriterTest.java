package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DocPipeManifestWriter}: the manifest is a JSON array of paths relative to the
 * project root (forward slashes, sorted); paths outside the project root are kept absolute.
 */
class DocPipeManifestWriterTest {

    @TempDir
    Path root;

    private ProjectDefinition pd;
    private ObjectMapper objectMapper;
    private DocPipeManifestWriter uut;

    @BeforeEach
    void setUp() {
        pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(root.toString());
        objectMapper = new ObjectMapper();
        uut = new DocPipeManifestWriter(objectMapper);
    }

    private String[] readManifest() throws Exception {
        Path manifest = root.resolve(DocPipeManifestWriter.MANIFEST_RELATIVE_PATH);
        assertThat(manifest).exists();
        return objectMapper.readValue(manifest.toFile(), String[].class);
    }

    @Test
    void writesRelativeForwardSlashPaths() throws Exception {
        Path a = root.resolve("docs").resolve("a.md");
        Path b = root.resolve("docs").resolve("sub").resolve("b.md");
        Files.createDirectories(b.getParent());
        Files.writeString(a, "a");
        Files.writeString(b, "b");

        uut.write(pd, List.of(a.toAbsolutePath().toString(), b.toAbsolutePath().toString()));

        assertThat(readManifest()).containsExactly("docs/a.md", "docs/sub/b.md");
    }

    @Test
    void outsideRootKeptAbsolute() throws Exception {
        Path sibling = Files.createDirectories(root.getParent().resolve(root.getFileName() + "-sibling"));
        Path outside = sibling.resolve("x.md");
        Files.writeString(outside, "x");

        uut.write(pd, List.of(outside.toAbsolutePath().toString()));

        assertThat(readManifest()).containsExactly(outside.toAbsolutePath().normalize().toString());
    }

    @Test
    void overwritesPreviousManifest() throws Exception {
        Path a = root.resolve("docs").resolve("a.md");
        Path b = root.resolve("docs").resolve("b.md");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "a");
        Files.writeString(b, "b");

        uut.write(pd, List.of(a.toAbsolutePath().toString(), b.toAbsolutePath().toString()));
        uut.write(pd, List.of(a.toAbsolutePath().toString()));

        assertThat(readManifest()).containsExactly("docs/a.md");
    }
}
