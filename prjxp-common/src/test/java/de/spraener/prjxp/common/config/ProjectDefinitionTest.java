package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDefinitionTest {

    @Test
    void fulltextDefaultsToFalse() {
        assertThat(new ProjectDefinition().isFulltext()).isFalse();
    }

    @Test
    void newDefinitionHasDependencyDefaults() {
        ProjectDefinition def = new ProjectDefinition();
        assertThat(def.isEmbedDependencies()).isFalse();
        assertThat(def.getDependencyDepth()).isEqualTo(2);
        assertThat(def.getDependencyWhitelist()).isEmpty();
        assertThat(def.getMavenDependency()).isNull();
    }

    @Test
    void dependencyFieldsCanBeSet() {
        ProjectDefinition def = new ProjectDefinition();
        def.setEmbedDependencies(true);
        def.setDependencyDepth(3);
        def.setDependencyWhitelist("org.springframework,com.google");
        def.setMavenDependency("org.x:y:1");
        assertThat(def.isEmbedDependencies()).isTrue();
        assertThat(def.getDependencyDepth()).isEqualTo(3);
        assertThat(def.getDependencyWhitelist()).isEqualTo("org.springframework,com.google");
        assertThat(def.getMavenDependency()).isEqualTo("org.x:y:1");
    }

    @Test
    void gitUrlDefaultsToNull() {
        assertThat(new ProjectDefinition().getGitUrl()).isNull();
    }

    @Test
    void gitUrlCanBeSet() {
        ProjectDefinition def = new ProjectDefinition();
        def.setGitUrl("https://github.com/foo/bar");
        assertThat(def.getGitUrl()).isEqualTo("https://github.com/foo/bar");
    }

    @Test
    void gitTokenDefaultsToNull() {
        assertThat(new ProjectDefinition().getGitToken()).isNull();
    }

    @Test
    void gitTokenCanBeSet() {
        ProjectDefinition def = new ProjectDefinition();
        def.setGitToken("v1:aa:bb:cc");
        assertThat(def.getGitToken()).isEqualTo("v1:aa:bb:cc");
    }

    @Test
    void resolvedJsonlFile_relativeResolvesAgainstRootDir() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("foo");
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("px-chunks.jsonl");

        assertThat(Path.of(pd.resolvedJsonlFile()).normalize())
                .isEqualTo(Path.of("/x/proj").resolve("px-chunks.jsonl").normalize());
    }

    @Test
    void resolvedJsonlFile_absolutePassesThroughUnchanged() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("/abs/out.jsonl");

        assertThat(Path.of(pd.resolvedJsonlFile()).normalize())
                .isEqualTo(Path.of("/abs/out.jsonl").normalize());
    }

    @Test
    void resolvedJsonlFile_nullReturnsNull() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");

        assertThat(pd.resolvedJsonlFile()).isNull();
    }

    @Test
    void resolvedJsonlFile_blankReturnsNull() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("   ");

        assertThat(pd.resolvedJsonlFile()).isNull();
    }

    @Test
    void resolvedJsonlFile_relativeWithBlankRootDirStaysCwdRelative() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("  ");
        pd.setJsonlFile("px-chunks.jsonl");

        // CWD-relative, as before (rootDir falls back to ".")
        assertThat(Path.of(pd.resolvedJsonlFile()).normalize())
                .isEqualTo(Path.of(".").resolve("px-chunks.jsonl").normalize());
    }

    @Test
    void resolvedJsonlFile_relativeWithNullRootDirStaysCwdRelative() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setJsonlFile("px-chunks.jsonl");

        assertThat(Path.of(pd.resolvedJsonlFile()).normalize())
                .isEqualTo(Path.of(".").resolve("px-chunks.jsonl").normalize());
    }

    @Test
    void resolvedGraphFile_explicitResolvesAgainstRootDir() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setGraphFile("my-graph.json");

        assertThat(Path.of(pd.resolvedGraphFile()).normalize())
                .isEqualTo(Path.of("/x/proj").resolve("my-graph.json").normalize());
    }

    @Test
    void resolvedGraphFile_derivedFromJsonlWhenUnset() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("px-chunks.jsonl");

        assertThat(Path.of(pd.resolvedGraphFile()).normalize())
                .isEqualTo(Path.of("/x/proj").resolve("px-chunks.graph.json").normalize());
    }

    @Test
    void resolvedGraphFile_explicitWinsOverDerived() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("px-chunks.jsonl");
        pd.setGraphFile("custom.json");

        assertThat(Path.of(pd.resolvedGraphFile()).normalize())
                .isEqualTo(Path.of("/x/proj").resolve("custom.json").normalize());
    }

    @Test
    void resolvedGraphFile_nullWhenNoJsonlAndNoGraphFile() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");

        assertThat(pd.resolvedGraphFile()).isNull();
    }

    @Test
    void resolvedGraphFile_absolutePassesThroughUnchanged() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setGraphFile("/abs/graph.json");

        assertThat(Path.of(pd.resolvedGraphFile()).normalize())
                .isEqualTo(Path.of("/abs/graph.json").normalize());
    }
}
