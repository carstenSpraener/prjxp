package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectoryVetosTest {

    private DirectoryVetos vetos;
    private VetoContext ctx;
    private ProjectDefinition def;

    @BeforeEach
    void setUp() {
        def = mock(ProjectDefinition.class);
        ctx = mock(VetoContext.class);
        when(ctx.get()).thenReturn(def);
        vetos = new DirectoryVetos(mock(PrjXPConfig.class), ctx);
    }

    @Test
    void nodeModulesDirectoryIsExcluded() {
        when(def.getExcludeDirNames()).thenReturn("node_modules");
        assertThat(vetos.isExcludedDir(Path.of("frontend/node_modules/sass/types/importer.d.ts"))).isTrue();
    }

    @Test
    void fileOutsideExcludedDirectoryIsNotExcluded() {
        when(def.getExcludeDirNames()).thenReturn("node_modules");
        assertThat(vetos.isExcludedDir(Path.of("frontend/src/app/importer.ts"))).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        when(def.getExcludeDirNames()).thenReturn("node_modules");
        assertThat(vetos.isExcludedDir(Path.of("frontend/Node_Modules/pkg/index.d.ts"))).isTrue();
    }

    @Test
    void matchesAnyPathSegmentNotJustTheFirst() {
        when(def.getExcludeDirNames()).thenReturn("dist");
        assertThat(vetos.isExcludedDir(Path.of("web/lib/dist/bundle.js"))).isTrue();
    }

    @Test
    void wholeSegmentMatchOnlySubstringIsNotExcluded() {
        // A file NAMED node_modules.txt in a normal dir must not be vetoed (segment != "node_modules").
        when(def.getExcludeDirNames()).thenReturn("node_modules");
        assertThat(vetos.isExcludedDir(Path.of("docs/node_modules.txt"))).isFalse();
    }

    @Test
    void multipleNamesAllApply() {
        when(def.getExcludeDirNames()).thenReturn("node_modules,.git,dist");
        assertThat(vetos.isExcludedDir(Path.of("a/node_modules/x.d.ts"))).isTrue();
        assertThat(vetos.isExcludedDir(Path.of("a/.git/config"))).isTrue();
        assertThat(vetos.isExcludedDir(Path.of("a/dist/out.js"))).isTrue();
        assertThat(vetos.isExcludedDir(Path.of("a/src/Main.java"))).isFalse();
    }

    @Test
    void blankListDoesNotVeto() {
        when(def.getExcludeDirNames()).thenReturn("");
        assertThat(vetos.isExcludedDir(Path.of("frontend/node_modules/x.d.ts"))).isFalse();
    }

    @Test
    void nullListDoesNotVeto() {
        when(def.getExcludeDirNames()).thenReturn(null);
        assertThat(vetos.isExcludedDir(Path.of("frontend/node_modules/x.d.ts"))).isFalse();
    }

    @Test
    void fallsBackToConfigActiveProjectWhenContextEmpty() {
        PrjXPConfig config = mock(PrjXPConfig.class);
        ProjectDefinition fallback = mock(ProjectDefinition.class);
        VetoContext emptyCtx = mock(VetoContext.class);
        when(emptyCtx.get()).thenReturn(null);
        when(config.getActiveProject()).thenReturn(Optional.of(fallback));
        when(fallback.getExcludeDirNames()).thenReturn("node_modules");
        vetos = new DirectoryVetos(config, emptyCtx);

        assertThat(vetos.isExcludedDir(Path.of("frontend/node_modules/x.d.ts"))).isTrue();
    }

    @Test
    void noContextAndNoActiveProjectDoesNotVeto() {
        PrjXPConfig config = mock(PrjXPConfig.class);
        VetoContext emptyCtx = mock(VetoContext.class);
        when(emptyCtx.get()).thenReturn(null);
        when(config.getActiveProject()).thenReturn(Optional.empty());
        vetos = new DirectoryVetos(config, emptyCtx);

        assertThat(vetos.isExcludedDir(Path.of("frontend/node_modules/x.d.ts"))).isFalse();
    }

    // ------------------------------------------------------------------ source-dir exception (positional)

    @Test
    void sourceTreeProtectsBuildNamedPackage() {
        // The nxtgen case: a source package named "target" deep inside src stays indexed.
        when(def.getExcludeDirNames()).thenReturn("target,build");
        assertThat(vetos.isExcludedDir(Path.of("src/main/java/de/spraener/nxtgen/target/CodeTarget.java"))).isFalse();
    }

    @Test
    void generatedSourcesInsideBuildDirAreVetoed() {
        // Edge case: src *behind* build — a copied/generated source tree inside build output.
        when(def.getExcludeDirNames()).thenReturn("target,build");
        assertThat(vetos.isExcludedDir(Path.of("build/generated/src/Foo.java"))).isTrue();
    }

    @Test
    void customSourceDirNamesProtectsThatTree() {
        when(def.getExcludeDirNames()).thenReturn("target,build");
        when(def.getSourceDirNames()).thenReturn("sources");
        assertThat(vetos.isExcludedDir(Path.of("sources/main/java/x/target/Foo.java"))).isFalse();
    }

    @Test
    void customSourceDirNamesReplacesDefault() {
        // With sourceDirNames=sources, a plain "src" dir is no longer sacred.
        when(def.getExcludeDirNames()).thenReturn("target,build");
        when(def.getSourceDirNames()).thenReturn("sources");
        assertThat(vetos.isExcludedDir(Path.of("src/main/java/x/target/Foo.java"))).isTrue();
    }

    @Test
    void isSourceDirectoryMatchesAnySegment() {
        when(def.getSourceDirNames()).thenReturn("src");
        assertThat(vetos.isSourceDirectory(Path.of("src/Main.java"))).isTrue();
        assertThat(vetos.isSourceDirectory(Path.of("lib/src/Main.java"))).isTrue();
        assertThat(vetos.isSourceDirectory(Path.of("lib/Main.java"))).isFalse();
    }

    @Test
    void isSourceDirectoryIsCaseInsensitive() {
        when(def.getSourceDirNames()).thenReturn("src");
        assertThat(vetos.isSourceDirectory(Path.of("SRC/Main.java"))).isTrue();
    }

    @Test
    void isSourceDirectoryWithoutDefinitionIsFalse() {
        PrjXPConfig config = mock(PrjXPConfig.class);
        VetoContext emptyCtx = mock(VetoContext.class);
        when(emptyCtx.get()).thenReturn(null);
        when(config.getActiveProject()).thenReturn(Optional.empty());
        DirectoryVetos v = new DirectoryVetos(config, emptyCtx);

        assertThat(v.isSourceDirectory(Path.of("src/Main.java"))).isFalse();
    }
}
