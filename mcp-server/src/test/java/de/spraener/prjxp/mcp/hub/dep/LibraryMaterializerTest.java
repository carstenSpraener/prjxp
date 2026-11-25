package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 05: {@link LibraryMaterializer} materializes a resolved dependency as an independent
 * SNAPSHOT library project under the libraries root (Maven-repo layout). The network client is
 * stubbed (no real I/O in tests); the config parser is real; the registry and orchestrator are mocked.
 */
class LibraryMaterializerTest {

    @TempDir
    Path tempDir;

    private HubProperties props;
    private StubMavenClient client;
    private HubProjectRegistry registry;
    private PipelineOrchestrator orchestrator;
    private LibraryMaterializer materializer;

    @BeforeEach
    void setUp() {
        props = new HubProperties();
        props.setLibrariesRoot(tempDir.resolve("libraries").toString());
        props.setMaxDependencyBytes(10 * 1024 * 1024);
        client = new StubMavenClient();
        registry = mock(HubProjectRegistry.class);
        orchestrator = mock(PipelineOrchestrator.class);
        materializer = new LibraryMaterializer(props, client, new ProjectConfigFileParser(), registry, orchestrator);
    }

    private ResolvedDependency dep(boolean hasSources, boolean hasJavadoc) {
        return new ResolvedDependency(new MavenCoordinates("com.example", "foo", "1.0"), 1, hasSources, hasJavadoc);
    }

    private Path libDir() {
        return materializer.libraryDir(dep(true, true));
    }

    // ------------------------------------------------------------------ libraryDir / isPopulated

    @Test
    void libraryDirUsesMavenRepoLayout() {
        assertThat(materializer.libraryDir(dep(true, true)))
                .isEqualTo(tempDir.resolve("libraries").resolve("com").resolve("example").resolve("foo").resolve("1.0"));
    }

    @Test
    void isPopulatedFalseForMissingDir() {
        assertThat(materializer.isPopulated(dep(true, true))).isFalse();
    }

    @Test
    void isPopulatedTrueWhenSrcHasFiles() throws Exception {
        Files.createDirectories(libDir().resolve("src").resolve("com").resolve("example"));
        Files.writeString(libDir().resolve("src").resolve("com").resolve("example").resolve("Foo.java"), "class Foo {}");

        assertThat(materializer.isPopulated(dep(true, true))).isTrue();
    }

    // ------------------------------------------------------------------ materialize

    @Test
    void materializesSourcesOnly() throws Exception {
        // multiple top-level segments -> no single-root strip, extracted as-is
        client.zipFor("sources", "com/example/Foo.java", "org/foo/Bar.java");
        when(registry.entry("foo-1.0")).thenReturn(Optional.empty());

        materializer.materialize(dep(true, false), false);

        assertThat(libDir().resolve("src").resolve("com").resolve("example").resolve("Foo.java")).exists();
        assertThat(libDir().resolve("src").resolve("org").resolve("foo").resolve("Bar.java")).exists();
        assertThat(libDir().resolve("doc")).doesNotExist();
        String yaml = Files.readString(libDir().resolve("prjxp.yaml"));
        assertThat(yaml).contains("name: foo-1.0");
        assertThat(yaml).contains("chunoWhiteList: java");
        assertThat(yaml).contains("mavenDependency: \"com.example:foo:1.0\"");   // the origin marker
        verify(registry).registerProject("foo-1.0", libDir(), ProjectEntry.Kind.SNAPSHOT, "library");
        verify(orchestrator).enqueueForced("foo-1.0");
    }

    @Test
    void materializesSourcesAndJavadoc() throws Exception {
        client.zipFor("sources", "com/example/Foo.java", "org/foo/Bar.java");
        client.zipFor("javadoc", "index.html");   // no slash -> no strip
        when(registry.entry("foo-1.0")).thenReturn(Optional.empty());

        materializer.materialize(dep(true, true), false);

        assertThat(libDir().resolve("src").resolve("com").resolve("example").resolve("Foo.java")).exists();
        assertThat(libDir().resolve("doc").resolve("index.html")).exists();
        String yaml = Files.readString(libDir().resolve("prjxp.yaml"));
        assertThat(yaml).contains("chunoWhiteList: java,html");
    }

    @Test
    void materializesJavadocOnly() throws Exception {
        client.zipFor("javadoc", "index.html");
        when(registry.entry("foo-1.0")).thenReturn(Optional.empty());

        materializer.materialize(dep(false, true), false);

        assertThat(libDir().resolve("doc").resolve("index.html")).exists();
        assertThat(libDir().resolve("src")).doesNotExist();
        String yaml = Files.readString(libDir().resolve("prjxp.yaml"));
        assertThat(yaml).contains("chunoWhiteList: html");
    }

    @Test
    void stripsSingleCommonRootSegment() throws Exception {
        // a sources jar whose entries all share one top-level dir must be extracted without it
        client.zipFor("sources", "foo-1.0/com/example/Foo.java");
        when(registry.entry("foo-1.0")).thenReturn(Optional.empty());

        materializer.materialize(dep(true, false), false);

        assertThat(libDir().resolve("src").resolve("com").resolve("example").resolve("Foo.java")).exists();
        assertThat(libDir().resolve("src").resolve("foo-1.0")).doesNotExist();
    }

    @Test
    void idempotentSkipsPopulatedLibrary() throws Exception {
        Files.createDirectories(libDir().resolve("src").resolve("com"));
        Files.writeString(libDir().resolve("src").resolve("com").resolve("Foo.java"), "class Foo {}");
        client.zipFor("sources", "com/example/Foo.java");

        materializer.materialize(dep(true, false), false);   // force=false

        assertThat(client.downloads).isEmpty();   // no download
        verify(registry, never()).registerProject(anyString(), any(), any(ProjectEntry.Kind.class), anyString());
        verify(orchestrator, never()).enqueueForced(anyString());
    }

    @Test
    void forceReMaterializesPopulatedLibrary() throws Exception {
        Files.createDirectories(libDir().resolve("src").resolve("com"));
        Files.writeString(libDir().resolve("src").resolve("com").resolve("Foo.java"), "class Foo {}");
        client.zipFor("sources", "com/example/Foo.java");
        when(registry.entry("foo-1.0")).thenReturn(Optional.empty());

        materializer.materialize(dep(true, false), true);   // force=true

        assertThat(client.downloads).containsExactly("sources");
        verify(registry).registerProject("foo-1.0", libDir(), ProjectEntry.Kind.SNAPSHOT, "library");
        verify(orchestrator).enqueueForced("foo-1.0");
    }

    @Test
    void reloadsDefinitionWhenAlreadyRegistered() throws Exception {
        client.zipFor("sources", "com/example/Foo.java");
        when(registry.entry("foo-1.0")).thenReturn(Optional.of(mock(ProjectEntry.class)));

        materializer.materialize(dep(true, false), false);

        verify(registry, never()).registerProject(anyString(), any(), any(ProjectEntry.Kind.class), anyString());
        verify(registry).reloadDefinition("foo-1.0");
        verify(orchestrator).enqueueForced("foo-1.0");
    }

    @Test
    void zipSlipEntryIsRejectedAndNothingWritten() throws Exception {
        client.zipFor("sources", "../evil.txt");

        assertThatThrownBy(() -> materializer.materialize(dep(true, false), false))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("..");

        assertThat(libDir().resolve("evil.txt")).doesNotExist();
        assertThat(libDir().getParent().resolve("evil.txt")).doesNotExist();   // nothing outside the target
    }

    @Test
    void archiveOverSizeLimitIsRejected() throws Exception {
        props.setMaxDependencyBytes(10);   // any real zip exceeds this
        client.zipFor("sources", "com/example/Foo.java");

        assertThatThrownBy(() -> materializer.materialize(dep(true, false), false))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void downloadFailurePropagates() {
        // no zip stubbed for "sources" -> the stub throws a MavenDependencyException

        assertThatThrownBy(() -> materializer.materialize(dep(true, false), false))
                .isInstanceOf(MavenDependencyException.class);
    }

    // ------------------------------------------------------------------ stub

    /** A {@link MavenCentralClient} stub that writes a canned zip instead of hitting the network. */
    private static class StubMavenClient extends MavenCentralClient {
        final List<String> downloads = new ArrayList<>();
        private final Map<String, List<String>> zips = new HashMap<>();

        StubMavenClient() {
            super("http://localhost:0", Duration.ofSeconds(1), Duration.ofSeconds(1), 10 * 1024 * 1024);
        }

        void zipFor(String classifier, String... entries) {
            zips.put(classifier, List.of(entries));
        }

        @Override
        public void download(MavenCoordinates c, String classifier, Path target) throws MavenDependencyException {
            downloads.add(classifier);
            List<String> entries = zips.get(classifier);
            if (entries == null) {
                throw new MavenDependencyException("No stubbed artifact for classifier '" + classifier + "'");
            }
            try {
                Files.createDirectories(target.getParent());
                try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(target))) {
                    for (String entry : entries) {
                        zout.putNextEntry(new ZipEntry(entry));
                        zout.write(("content of " + entry).getBytes(StandardCharsets.UTF_8));
                        zout.closeEntry();
                    }
                }
            } catch (IOException e) {
                throw new MavenDependencyException("Stub failed to write " + target + ": " + e.getMessage(), e);
            }
        }
    }
}
