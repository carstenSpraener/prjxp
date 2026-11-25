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
 * {@link LiveSource}: passive, recursive marker discovery of {@code prjxp.hub.import-dir} with
 * volume-glitch protection (missing/unlistable root -> null, skip the sync) and UNREGISTER_AND_WIPE vanish.
 */
class LiveSourceTest {

    @TempDir
    Path tempDir;

    private HubProperties hub;
    private LiveSource source;
    private Path importDir;

    @BeforeEach
    void setUp() throws IOException {
        hub = new HubProperties();
        importDir = Files.createDirectories(tempDir.resolve("import"));
        hub.setImportDir(importDir.toString());
        source = new LiveSource(hub, new MarkerWalker(hub, new ProjectConfigFileParser()));
    }

    @Test
    void exposesLiveContract() {
        assertThat(source.name()).isEqualTo("live");
        assertThat(source.kind()).isEqualTo(ProjectEntry.Kind.LIVE);
        assertThat(source.vanishPolicy()).isEqualTo(VanishPolicy.UNREGISTER_AND_WIPE);
        assertThat(source.markerField()).isNull();
        assertThat(source.root()).isEqualTo(importDir);
    }

    @Test
    void discoverFindsMarkerDirUnderImportDir() throws IOException {
        Path foo = Files.createDirectories(importDir.resolve("foo"));
        Files.writeString(foo.resolve("prjxp.yaml"), "name: foo\n");

        Map<String, Path> found = source.discover();

        assertThat(found).containsEntry("foo", foo).hasSize(1);
    }

    @Test
    void discoverWithMissingImportDirReturnsNullAndRecoversAfterReappearance() throws IOException {
        hub.setImportDir(tempDir.resolve("missing").toString());

        assertThat(source.discover()).isNull();   // volume-glitch: skip the sync entirely

        Files.createDirectories(tempDir.resolve("missing"));   // the volume is back

        Map<String, Path> found = source.discover();

        assertThat(found).isNotNull().isEmpty();   // the missing-warn throttle reset — the dir is available again
    }
}
