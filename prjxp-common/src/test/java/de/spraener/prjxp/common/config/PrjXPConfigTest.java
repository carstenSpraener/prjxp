package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static de.spraener.prjxp.common.test.PrjXPTestObjectMother.createPrjXPConfig;
import static de.spraener.prjxp.common.test.PrjXPTestObjectMother.createProjectDefinition;
import static org.assertj.core.api.Assertions.assertThat;

class PrjXPConfigTest {

    @Test
    void getProjectDefinition_withCwdAndEmptyProjects_returnsFallback() {
        PrjXPConfig config = createPrjXPConfig();

        Optional<ProjectDefinition> result = config.getProjectDefinition("cwd");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("cwd");
        assertThat(result.get().getRootDir()).isEqualTo(".");
        assertThat(result.get().getJsonlFile()).isEqualTo("px-chunks.jsonl");
        assertThat(result.get().getChunoWhiteList()).isEqualTo("java,ts");
        assertThat(result.get().getTibedBatchSize()).isEqualTo(50);
        assertThat(result.get().isTibedResetStore()).isTrue();
    }

    @Test
    void getProjectDefinition_withCwdAndNonEmptyProjects_returnsMatchingProject() {
        PrjXPConfig config = createPrjXPConfig(c -> c.setProjects(List.of(
                createProjectDefinition(p -> {
                    p.setName("cwd");
                    p.setRootDir("/custom/cwd");
                })
        )));

        Optional<ProjectDefinition> result = config.getProjectDefinition("cwd");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("cwd");
        assertThat(result.get().getRootDir()).isEqualTo("/custom/cwd");
    }

    @Test
    void getProjectDefinition_withMatchingName_returnsProject() {
        PrjXPConfig config = createPrjXPConfig(c -> c.setProjects(List.of(
                createProjectDefinition(p -> {
                    p.setName("myproject");
                    p.setRootDir("/projects/myproject");
                    p.setJsonlFile("custom.jsonl");
                })
        )));

        Optional<ProjectDefinition> result = config.getProjectDefinition("myproject");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("myproject");
        assertThat(result.get().getRootDir()).isEqualTo("/projects/myproject");
        assertThat(result.get().getJsonlFile()).isEqualTo("custom.jsonl");
    }

    @Test
    void getProjectDefinition_withNonMatchingName_returnsEmpty() {
        PrjXPConfig config = createPrjXPConfig(c -> c.setProjects(List.of(
                createProjectDefinition(p -> p.setName("project1"))
        )));

        Optional<ProjectDefinition> result = config.getProjectDefinition("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void getProjectDefinition_withEmptyProjectsAndNonMatchingName_returnsEmpty() {
        PrjXPConfig config = createPrjXPConfig();

        Optional<ProjectDefinition> result = config.getProjectDefinition("other");

        assertThat(result).isEmpty();
    }

    @Test
    void getActiveProject_withDefaultCwdAndEmptyProjects_returnsFallback() {
        PrjXPConfig config = createPrjXPConfig();

        Optional<ProjectDefinition> result = config.getActiveProject();

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("cwd");
    }

    @Test
    void getActiveProject_withCustomActiveProject_returnsMatchingProject() {
        PrjXPConfig config = createPrjXPConfig(c -> {
            c.setActiveProject("myproject");
            c.setProjects(List.of(
                    createProjectDefinition(p -> {
                        p.setName("myproject");
                        p.setRootDir("/projects/myproject");
                    })
            ));
        });

        Optional<ProjectDefinition> result = config.getActiveProject();

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("myproject");
        assertThat(result.get().getRootDir()).isEqualTo("/projects/myproject");
    }

    @Test
    void getActiveProject_withNonMatchingActiveProject_returnsEmpty() {
        PrjXPConfig config = createPrjXPConfig(c -> {
            c.setActiveProject("nonexistent");
            c.setProjects(List.of(
                    createProjectDefinition(p -> p.setName("project1"))
            ));
        });

        Optional<ProjectDefinition> result = config.getActiveProject();

        assertThat(result).isEmpty();
    }

    @Test
    void getActiveProject_withEmptyProjectsAndNonCwd_returnsEmpty() {
        PrjXPConfig config = createPrjXPConfig(c -> c.setActiveProject("other"));

        Optional<ProjectDefinition> result = config.getActiveProject();

        assertThat(result).isEmpty();
    }
}
