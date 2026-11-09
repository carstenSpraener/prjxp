package de.spraener.prjxp.mcp.hub.dep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 04: {@link BuildGradleParser} — regex fallback over build.gradle / build.gradle.kts
 * (direct dependencies only, all at depth 1). Pure file-based tests: no network, no Gradle.
 */
class BuildGradleParserTest {

    private final BuildGradleParser parser = new BuildGradleParser();

    @TempDir
    Path tempDir;

    @Test
    void parsesGroovyImplementationAndApi() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                dependencies {
                    implementation "org.jsoup:jsoup:1.23.2"
                    api "com.google.guava:guava:31.1-jre"
                }
                """);

        List<RawDependency> deps = parser.parse(tempDir);

        assertThat(deps).containsExactly(
                new RawDependency(new MavenCoordinates("org.jsoup", "jsoup", "1.23.2"), 1),
                new RawDependency(new MavenCoordinates("com.google.guava", "guava", "31.1-jre"), 1));
    }

    @Test
    void parsesKotlinParenthesizedForm() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("org.x:y:2.0")
                }
                """);

        assertThat(parser.parse(tempDir)).containsExactly(
                new RawDependency(new MavenCoordinates("org.x", "y", "2.0"), 1));
    }

    @Test
    void capturesCompileAndRuntimeOnly() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                dependencies {
                    compile "a:b:1"
                    runtimeOnly "c:d:4"
                }
                """);

        assertThat(parser.parse(tempDir)).containsExactly(
                new RawDependency(new MavenCoordinates("a", "b", "1"), 1),
                new RawDependency(new MavenCoordinates("c", "d", "4"), 1));
    }

    @Test
    void ignoresNonDependencyConfigurations() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                dependencies {
                    testImplementation "junit:junit:5"
                }
                """);

        assertThat(parser.parse(tempDir)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoBuildFileExists() {
        assertThat(parser.parse(tempDir)).isEmpty();
    }
}
