package de.spraener.prjxp.mcp.hub.dep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 04: {@link GradleDependencyResolver} — runs a fake {@code gradle} executable
 * (a shell script in a temp dir, injected via the test constructor) so no real Gradle
 * build and no network are involved.
 */
class GradleDependencyResolverTest {

    private static final int TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    private Path writeExecutable(String name, String script) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private GradleDependencyResolver resolverWith(Path gradleExecutable) {
        return new GradleDependencyResolver(new BuildGradleParser(), TIMEOUT_SECONDS, gradleExecutable);
    }

    private static DependencyResolveConfig config(int maxDepth) {
        return new DependencyResolveConfig(maxDepth, List.of());
    }

    @Test
    void supportsTrueWhenBuildGradleExists() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "");

        assertThat(resolverWith(Path.of("gradle")).supports(tempDir)).isTrue();
    }

    @Test
    void supportsTrueWhenBuildGradleKtsExists() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "");

        assertThat(resolverWith(Path.of("gradle")).supports(tempDir)).isTrue();
    }

    @Test
    void supportsFalseWithoutBuildFile() {
        assertThat(resolverWith(Path.of("gradle")).supports(tempDir)).isFalse();
    }

    @Test
    void resolveParsesGradleTreeOutputWithDepths() throws Exception {
        Path fakeGradle = writeExecutable("gradle", """
                #!/bin/sh
                echo "runtimeClasspath"
                echo "+--- org.jsoup:jsoup:1.23.2"
                echo "|    \\--- com.example:transitive:1.0"
                echo "\\--- com.google.guava:guava:31.1-jre"
                """);
        Files.writeString(tempDir.resolve("build.gradle"), "dependencies { }");

        DependencyResolution res = resolverWith(fakeGradle).resolve(tempDir, config(10));

        assertThat(res.raw()).containsExactly(
                new RawDependency(new MavenCoordinates("org.jsoup", "jsoup", "1.23.2"), 1),
                new RawDependency(new MavenCoordinates("com.example", "transitive", "1.0"), 2),
                new RawDependency(new MavenCoordinates("com.google.guava", "guava", "31.1-jre"), 1));
    }

    @Test
    void resolveFallsBackToBuildGradleParserWhenGradleFails() throws Exception {
        Path failingGradle = writeExecutable("gradle", "#!/bin/sh\nexit 1\n");
        Files.writeString(tempDir.resolve("build.gradle"), """
                dependencies {
                    implementation "org.jsoup:jsoup:1.23.2"
                }
                """);

        DependencyResolution res = resolverWith(failingGradle).resolve(tempDir, config(10));

        assertThat(res.raw()).containsExactly(
                new RawDependency(new MavenCoordinates("org.jsoup", "jsoup", "1.23.2"), 1));
    }

    @Test
    void parseTreeStripsVersionConstraintAndSkipsHeaderAndMalformedLines() {
        GradleDependencyResolver resolver = resolverWith(Path.of("gradle"));

        List<RawDependency> deps = resolver.parseTree("""
                runtimeClasspath
                +--- org.jsoup:jsoup:1.23.2 (1.24.0)
                \\--- com.example:malformed
                """);

        assertThat(deps).containsExactly(
                new RawDependency(new MavenCoordinates("org.jsoup", "jsoup", "1.23.2"), 1));
    }
}
