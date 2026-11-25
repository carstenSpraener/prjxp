package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SnapshotSource}: passive, one-level discovery of {@code prjxp.hub.projects-root},
 * UNREGISTER_ONLY vanish, empty map (never null) for a missing root.
 */
class SnapshotSourceTest {

    @TempDir
    Path tempDir;

    private HubProperties hub;
    private SnapshotSource source;
    private Path projectsRoot;

    @BeforeEach
    void setUp() throws IOException {
        hub = new HubProperties();
        projectsRoot = Files.createDirectories(tempDir.resolve("projects"));
        hub.setProjectsRoot(projectsRoot.toString());
        source = new SnapshotSource(hub, new MarkerWalker(hub, new ProjectConfigFileParser()));
    }

    @Test
    void exposesSnapshotContract() {
        assertThat(source.name()).isEqualTo("snapshot");
        assertThat(source.kind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);
        assertThat(source.vanishPolicy()).isEqualTo(VanishPolicy.UNREGISTER_ONLY);
        assertThat(source.markerField()).isNull();
        assertThat(source.root()).isEqualTo(projectsRoot);
    }

    @Test
    void discoverReturnsImmediateSubdirsKeyedByDirName() throws IOException {
        Path alpha = Files.createDirectories(projectsRoot.resolve("alpha"));
        Path beta = Files.createDirectories(projectsRoot.resolve("beta"));

        Map<String, Path> found = source.discover();

        assertThat(found).containsEntry("alpha", alpha).containsEntry("beta", beta).hasSize(2);
    }

    @Test
    void discoverWithMissingRootReturnsEmptyMap() {
        hub.setProjectsRoot(tempDir.resolve("does-not-exist").toString());

        Map<String, Path> found = source.discover();

        assertThat(found).isNotNull().isEmpty();   // missing root -> empty (unregister vanished), NOT null
    }
}
