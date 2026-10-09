package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandardVetosTest {

    private StandardVetos vetos;

    @BeforeEach
    void setUp() {
        vetos = new StandardVetos(mock(PrjXPConfig.class), mock(VetoContext.class));
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

    @Test
    void whiteListAllowsMatchingExtension() {
        de.spraener.prjxp.common.config.ProjectDefinition def = mock(de.spraener.prjxp.common.config.ProjectDefinition.class);
        VetoContext ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(def);
        when(def.getChunoWhiteList()).thenReturn("java,vb");
        vetos = new StandardVetos(mock(PrjXPConfig.class), ctx);

        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.vb"))).isFalse();
        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.java"))).isFalse();
    }

    @Test
    void whiteListVetoesNonMatchingExtension() {
        de.spraener.prjxp.common.config.ProjectDefinition def = mock(de.spraener.prjxp.common.config.ProjectDefinition.class);
        VetoContext ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(def);
        when(def.getChunoWhiteList()).thenReturn("java,vb");
        vetos = new StandardVetos(mock(PrjXPConfig.class), ctx);

        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.ts"))).isTrue();
        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.md"))).isTrue();
    }

    @Test
    void whiteListEmptyFallsBackToConfigActiveProject() {
        PrjXPConfig config = mock(PrjXPConfig.class);
        de.spraener.prjxp.common.config.ProjectDefinition fallbackDef = mock(de.spraener.prjxp.common.config.ProjectDefinition.class);
        VetoContext ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(null);  // no context set
        when(config.getActiveProject()).thenReturn(Optional.of(fallbackDef));
        when(fallbackDef.getChunoWhiteList()).thenReturn("java,ts");
        vetos = new StandardVetos(config, ctx);

        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.java"))).isFalse();
        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.vb"))).isTrue();
    }

    @Test
    void whiteListNullInContextAndConfigDoesNotVeto() {
        PrjXPConfig config = mock(PrjXPConfig.class);
        VetoContext ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(null);
        when(config.getActiveProject()).thenReturn(Optional.empty());
        vetos = new StandardVetos(config, ctx);

        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.vb"))).isFalse();
    }

    @Test
    void whiteListEmptyStringDoesNotVeto() {
        de.spraener.prjxp.common.config.ProjectDefinition def = mock(de.spraener.prjxp.common.config.ProjectDefinition.class);
        VetoContext ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(def);
        when(def.getChunoWhiteList()).thenReturn("");
        vetos = new StandardVetos(mock(PrjXPConfig.class), ctx);

        assertThat(vetos.notListedInWhiteList(Path.of("src/Main.vb"))).isFalse();
    }
}
