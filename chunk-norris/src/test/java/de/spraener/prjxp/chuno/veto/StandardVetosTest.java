package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StandardVetosTest {

    private StandardVetos vetos;

    @BeforeEach
    void setUp() {
        vetos = new StandardVetos(mock(PrjXPConfig.class));
    }

    @Test
    void sourceFileInPackageNamedTargetIsNotBuildArtifact() {
        Path p = Path.of("src/main/java/de/spraener/nxtgen/target/CodeTarget.java");
        assertThat(vetos.isBuildArtifact(p)).isFalse();
    }

    @Test
    void gradleBuildDirectoryIsBuildArtifact() {
        Path p = Path.of("cgv19-core/build/tmp/whatever.class");
        assertThat(vetos.isBuildArtifact(p)).isTrue();
    }

    @Test
    void mavenTargetDirectoryIsBuildArtifact() {
        Path p = Path.of("module/target/classes/Foo.class");
        assertThat(vetos.isBuildArtifact(p)).isTrue();
    }

    @Test
    void fileNameContainingTargetSubstringIsNotBuildArtifact() {
        Path p = Path.of("src/main/java/com/foo/MyTarget.java");
        assertThat(vetos.isBuildArtifact(p)).isFalse();
    }

    @Test
    void buildDirectoryUnderSrcIsNotBuildArtifact() {
        Path p = Path.of("src/test/resources/build/data.txt");
        assertThat(vetos.isBuildArtifact(p)).isFalse();
    }

    @Test
    void bareTargetSegmentIsBuildArtifact() {
        Path p = Path.of("target");
        assertThat(vetos.isBuildArtifact(p)).isTrue();
    }
}
