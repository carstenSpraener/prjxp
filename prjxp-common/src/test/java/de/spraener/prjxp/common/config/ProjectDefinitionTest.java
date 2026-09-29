package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDefinitionTest {

    @Test
    void resolvedJsonlFile_relativeResolvesAgainstRootDir() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("foo");
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("px-chunks.jsonl");

        assertThat(pd.resolvedJsonlFile()).isEqualTo("/x/proj/px-chunks.jsonl");
    }

    @Test
    void resolvedJsonlFile_absolutePassesThroughUnchanged() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setRootDir("/x/proj");
        pd.setJsonlFile("/abs/out.jsonl");

        assertThat(pd.resolvedJsonlFile()).isEqualTo("/abs/out.jsonl");
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
        assertThat(pd.resolvedJsonlFile()).isEqualTo("./px-chunks.jsonl");
    }

    @Test
    void resolvedJsonlFile_relativeWithNullRootDirStaysCwdRelative() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setJsonlFile("px-chunks.jsonl");

        assertThat(pd.resolvedJsonlFile()).isEqualTo("./px-chunks.jsonl");
    }
}
