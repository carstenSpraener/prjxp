package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrjXPConfigProjectTest {

    @Test
    void requireProject_knownName_returnsIt() {
        PrjXPConfig cfg = new PrjXPConfig();
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("foo");
        cfg.getProjects().add(pd);

        assertThat(cfg.requireProject("foo")).isSameAs(pd);
    }

    @Test
    void requireProject_unknownName_failsWithAvailableList() {
        PrjXPConfig cfg = new PrjXPConfig();
        ProjectDefinition foo = new ProjectDefinition();
        foo.setName("foo");
        cfg.getProjects().add(foo);
        ProjectDefinition bar = new ProjectDefinition();
        bar.setName("bar");
        cfg.getProjects().add(bar);

        assertThatThrownBy(() -> cfg.requireProject("baz"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baz")
                .hasMessageContaining("foo")
                .hasMessageContaining("bar");
    }

    @Test
    void requireProject_emptyProjectsAndCwd_returnsSyntheticFallback() {
        PrjXPConfig cfg = new PrjXPConfig();

        ProjectDefinition cwd = cfg.requireProject("cwd");

        assertThat(cwd.getName()).isEqualTo("cwd");
        assertThat(cwd.getRootDir()).isEqualTo(".");
        assertThat(cwd.isTibedResetStore()).isTrue();
    }

    @Test
    void getActiveProjectName_returnsRawConfiguredName() {
        PrjXPConfig cfg = new PrjXPConfig();

        assertThat(cfg.getActiveProjectName()).isEqualTo("cwd");

        cfg.setActiveProject("foo");
        assertThat(cfg.getActiveProjectName()).isEqualTo("foo");
    }
}
