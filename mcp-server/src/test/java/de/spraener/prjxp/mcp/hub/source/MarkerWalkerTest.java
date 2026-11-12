package de.spraener.prjxp.mcp.hub.source;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkerWalker} is the shared, stateless discovery walk: one-level {@code listSubdirs}
 * (Snapshot) and recursive marker {@code walkMarkers} (Live; reused by Library and GitHub).
 * Uses a real {@link ProjectConfigFileParser} and a {@code @TempDir} tree.
 */
class MarkerWalkerTest {

    @TempDir
    Path tempDir;

    private HubProperties hub;
    private MarkerWalker walker;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        hub = new HubProperties();
        hub.setProjectsRoot(tempDir.resolve("projects").toString());
        hub.setImportDir(tempDir.resolve("import").toString());
        walker = new MarkerWalker(hub, new ProjectConfigFileParser());
        root = Files.createDirectories(tempDir.resolve("scan-root"));
    }

    private Path markerDir(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("prjxp.yaml"), "name: " + name + "\n");
        return dir;
    }

    // ------------------------------------------------------------------ listSubdirs (Snapshot)

    @Test
    void listSubdirsReturnsImmediateSubdirsKeyedByDirName() throws IOException {
        Path alpha = Files.createDirectories(root.resolve("alpha"));
        Path beta = Files.createDirectories(root.resolve("beta"));

        Map<String, Path> found = walker.listSubdirs(root);

        assertThat(found).containsEntry("alpha", alpha).containsEntry("beta", beta).hasSize(2);
    }

    @Test
    void listSubdirsWithMissingRootReturnsEmptyMap() {
        Map<String, Path> found = walker.listSubdirs(tempDir.resolve("does-not-exist"));

        assertThat(found).isNotNull().isEmpty();   // missing root -> empty (caller unregisters vanished), NOT null
    }

    @Test
    void listSubdirsIgnoresFiles() throws IOException {
        Files.writeString(root.resolve("stray.txt"), "not a project");
        Path alpha = Files.createDirectories(root.resolve("alpha"));

        Map<String, Path> found = walker.listSubdirs(root);

        assertThat(found).containsOnlyKeys("alpha");
    }

    // ------------------------------------------------------------------ walkMarkers (Live)

    @Test
    void walkMarkersFindsNestedMarkerDirKeyedByYamlName() throws IOException {
        Path nested = markerDir(root.resolve("teamA").resolve("projekt-x"), "x");

        Map<String, Path> found = walker.walkMarkers(root);

        assertThat(found).containsEntry("x", nested).hasSize(1);
    }

    @Test
    void walkMarkersIgnoresDirsWithoutMarker() throws IOException {
        Files.createDirectories(root.resolve("plain"));   // no prjxp.yaml/yml — the walk descends past it

        Map<String, Path> found = walker.walkMarkers(root);

        assertThat(found).isEmpty();
    }

    @Test
    void walkMarkersWithMissingRootReturnsNull() {
        assertThat(walker.walkMarkers(tempDir.resolve("does-not-exist"))).isNull();   // volume-glitch: skip the sync
    }

    @Test
    void walkMarkersPrunesExcludedDirNames() throws IOException {
        markerDir(root.resolve("node_modules").resolve("pkg"), "ghost");   // node_modules is in the default scanExcludeDirNames

        Map<String, Path> found = walker.walkMarkers(root);

        assertThat(found).isEmpty();   // pruned before the marker check
    }

    @Test
    void walkMarkersStopsAtMaxDepth() throws IOException {
        hub.setScanMaxDepth(2);
        markerDir(root.resolve("a").resolve("b").resolve("c"), "too-deep");   // depth 3 > max 2

        assertThat(walker.walkMarkers(root)).isEmpty();
    }

    @Test
    void walkMarkersFindsMarkerExactlyAtMaxDepth() throws IOException {
        hub.setScanMaxDepth(2);
        Path exact = markerDir(root.resolve("a").resolve("b"), "exact");   // depth 2 == max — the marker check precedes the cutoff

        assertThat(walker.walkMarkers(root)).containsEntry("exact", exact);
    }

    @Test
    void walkMarkersOutermostMarkerWins() throws IOException {
        Path mono = markerDir(root.resolve("mono"), "mono");
        markerDir(mono.resolve("svc-a"), "svc-a");   // nested marker — part of mono, never discovered separately

        Map<String, Path> found = walker.walkMarkers(root);

        assertThat(found).containsOnlyKeys("mono");
    }

    @Test
    void walkMarkersTerminatesOnSymlinkCycle() throws Exception {
        Path a = Files.createDirectories(root.resolve("a"));
        Path link = a.resolve("link");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), a.toString())
                    .redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue())
                        .as(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .isZero();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } else {
            Files.createSymbolicLink(link, a);
        }
        try {
            assertThat(walker.walkMarkers(root)).isEmpty();
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void walkMarkersWithExcludeMarkerAtRootReturnsEmptyMap() throws IOException {
        Files.writeString(root.resolve(ProjectConfigFileParser.EXCLUDE_MARKER_FILE_NAME), "");

        Map<String, Path> found = walker.walkMarkers(root);

        assertThat(found).isNotNull().isEmpty();   // the dir exists but is excluded — empty, NOT null
    }
}
