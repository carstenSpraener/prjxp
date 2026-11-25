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
 * Phase 03: {@link GithubSource} is the fourth {@link ProjectSource} — discovery of hub-cloned
 * GitHub projects under {@code prjxp.hub.github-root}. Uses a REAL {@link MarkerWalker} against a
 * temp dir (no Spring context).
 */
class GithubSourceTest {

    @TempDir
    Path tempDir;

    private HubProperties hub;
    private GithubSource source;

    @BeforeEach
    void setUp() {
        hub = new HubProperties();
        hub.setGithubRoot(tempDir.resolve("github").toString());   // NOT created — tests create it as needed
        source = new GithubSource(hub, new MarkerWalker(hub, new ProjectConfigFileParser()));
    }

    @Test
    void identityMethods() {
        assertThat(source.name()).isEqualTo("github");
        assertThat(source.root()).isEqualTo(Path.of(hub.getGithubRoot()));
        assertThat(source.kind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);   // hub-owned, but mutable via pull
        assertThat(source.vanishPolicy()).isEqualTo(VanishPolicy.UNREGISTER_AND_WIPE);
        assertThat(source.markerField()).isEqualTo("gitUrl");
    }

    @Test
    void discoverFindsCloneWithMarkerKeyedByYamlName() throws Exception {
        Path clone = Files.createDirectories(Path.of(hub.getGithubRoot()).resolve("bar"));
        Files.writeString(clone.resolve("prjxp.yaml"),
                "name: bar\ngitUrl: \"https://github.com/foo/bar\"\n");

        Map<String, Path> found = source.discover();

        assertThat(found).containsOnlyKeys("bar");   // keyed by the yaml name, not the dir name
        assertThat(found.get("bar")).isEqualTo(clone);
    }

    @Test
    void missingRootYieldsNullAndThrottleResetsWhenRootAppears() throws Exception {
        Path root = Path.of(hub.getGithubRoot());

        assertThat(source.discover()).isNull();   // volume-glitch: skip the sync (NEVER wipe)
        assertThat(source.discover()).isNull();   // still missing -> still null (warn throttled)

        Files.createDirectories(root);
        Path clone = Files.createDirectories(root.resolve("bar"));
        Files.writeString(clone.resolve("prjxp.yaml"), "name: bar\n");

        assertThat(source.discover()).containsOnlyKeys("bar");   // root back -> resume (throttle reset)
    }
}
