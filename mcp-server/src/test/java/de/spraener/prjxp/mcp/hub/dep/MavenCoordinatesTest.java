package de.spraener.prjxp.mcp.hub.dep;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 02: pure derivation logic of {@link MavenCoordinates} — no I/O.
 */
class MavenCoordinatesTest {

    private final MavenCoordinates jsoup = new MavenCoordinates("org.jsoup", "jsoup", "1.23.2");

    @Test
    void gavJoinsGroupArtifactVersion() {
        assertThat(jsoup.gav()).isEqualTo("org.jsoup:jsoup:1.23.2");
    }

    @Test
    void nameIsArtifactVersion() {
        assertThat(jsoup.name()).isEqualTo("jsoup-1.23.2");
    }

    @Test
    void repoPathConvertsGroupDotsToSlashes() {
        assertThat(jsoup.repoPath()).isEqualTo(Path.of("org", "jsoup", "jsoup", "1.23.2"));
    }

    @Test
    void pomUrlPointsAtPomArtifact() {
        assertThat(jsoup.pomUrl("https://repo1.maven.org/maven2"))
                .isEqualTo("https://repo1.maven.org/maven2/org/jsoup/jsoup/1.23.2/jsoup-1.23.2.pom");
    }

    @Test
    void sourcesUrlPointsAtSourcesJar() {
        assertThat(jsoup.sourcesUrl("https://repo1.maven.org/maven2"))
                .isEqualTo("https://repo1.maven.org/maven2/org/jsoup/jsoup/1.23.2/jsoup-1.23.2-sources.jar");
    }

    @Test
    void javadocUrlPointsAtJavadocJar() {
        assertThat(jsoup.javadocUrl("https://repo1.maven.org/maven2"))
                .isEqualTo("https://repo1.maven.org/maven2/org/jsoup/jsoup/1.23.2/jsoup-1.23.2-javadoc.jar");
    }
}
