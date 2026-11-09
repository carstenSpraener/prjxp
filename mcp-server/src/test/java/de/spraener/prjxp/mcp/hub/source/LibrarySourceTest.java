package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 05: {@link LibrarySource} is the third {@link ProjectSource} — hub-generated library
 * projects under the libraries root (Maven-repo layout), discovered by the shared marker walk.
 */
class LibrarySourceTest {

    @TempDir
    Path tempDir;

    private HubProperties hub;
    private LibrarySource source;

    @BeforeEach
    void setUp() {
        hub = new HubProperties();
        hub.setLibrariesRoot(tempDir.resolve("libraries").toString());
        MarkerWalker walker = new MarkerWalker(hub, new ProjectConfigFileParser());
        source = new LibrarySource(hub, walker);
    }

    @Test
    void sourceMetadata() {
        assertThat(source.name()).isEqualTo("library");
        assertThat(source.kind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);   // shares the kind with Snapshot — the source name disambiguates
        assertThat(source.vanishPolicy()).isEqualTo(VanishPolicy.UNREGISTER_ONLY);   // hub-owned: no index wipe
        assertThat(source.markerField()).isEqualTo("mavenDependency");
        assertThat(source.root()).isEqualTo(tempDir.resolve("libraries"));
    }

    @Test
    void discoverFindsLibraryDirWithMarkerKeyedByYamlName() throws Exception {
        Path libDir = tempDir.resolve("libraries").resolve("com").resolve("example").resolve("foo").resolve("1.0");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("prjxp.yaml"), "name: foo-1.0\nmavenDependency: com.example:foo:1.0\n");

        Map<String, Path> found = source.discover();

        assertThat(found).containsKey("foo-1.0");
        assertThat(found.get("foo-1.0")).isEqualTo(libDir);
    }

    @Test
    void discoverWithMissingRootReturnsEmptyMapNotNull() {
        // the libraries root was never created (it is optional)
        Map<String, Path> found = source.discover();

        assertThat(found).isNotNull();   // NOT null — an absent optional root must not be treated as a volume glitch
        assertThat(found).isEmpty();
    }

    @Test
    void discoverIgnoresDirsWithoutMarker() throws Exception {
        Path libDir = tempDir.resolve("libraries").resolve("com").resolve("example").resolve("foo").resolve("1.0");
        Files.createDirectories(libDir);   // no prjxp.yaml — not a project

        assertThat(source.discover()).isEmpty();
    }

    @Test
    void discoverIgnoresNestedMarkersBelowOuterMarker() throws Exception {
        // outermost marker wins (shared walk semantics): a marker nested inside a library dir is part of it
        Path libDir = tempDir.resolve("libraries").resolve("com").resolve("example").resolve("foo").resolve("1.0");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("prjxp.yaml"), "name: foo-1.0\n");
        Path nested = Files.createDirectories(libDir.resolve("nested"));
        Files.writeString(nested.resolve("prjxp.yaml"), "name: nested\n");

        Map<String, Path> found = source.discover();

        assertThat(found).containsOnlyKeys("foo-1.0");
    }
}
