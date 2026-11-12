package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.mcp.testsupport.TestProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 04: {@link GradleDependencyResolver} — runs a fake {@code gradle} executable
 * (a JVM subprocess, injected via the test constructor) so no real Gradle
 * build and no network are involved.
 */
class GradleDependencyResolverTest {

    private static final int TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    private GradleDependencyResolver resolverWith(Path gradleExecutable) {
        return new GradleDependencyResolver(new BuildGradleParser(), TIMEOUT_SECONDS, gradleExecutable);
    }

    private GradleDependencyResolver resolverWith(List<String> command) {
        return new GradleDependencyResolver(new BuildGradleParser(), TIMEOUT_SECONDS, command);
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
        List<String> fakeGradle = TestProcess.output(tempDir.resolve("gradle"), """
                runtimeClasspath
                +--- org.jsoup:jsoup:1.23.2
                |    \\--- com.example:transitive:1.0
                \\--- com.google.guava:guava:31.1-jre
                """, 0);
        Files.writeString(tempDir.resolve("build.gradle"), "dependencies { }");

        DependencyResolution res = resolverWith(fakeGradle).resolve(tempDir, config(10));

        assertThat(res.raw()).containsExactly(
                new RawDependency(new MavenCoordinates("org.jsoup", "jsoup", "1.23.2"), 1),
                new RawDependency(new MavenCoordinates("com.example", "transitive", "1.0"), 2),
                new RawDependency(new MavenCoordinates("com.google.guava", "guava", "31.1-jre"), 1));
    }

    @Test
    void resolveFallsBackToBuildGradleParserWhenGradleFails() throws Exception {
        List<String> failingGradle = TestProcess.output(tempDir.resolve("gradle"), "", 1);
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
