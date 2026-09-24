package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 02: {@link ProjectRegistry} is the single source of truth for project resolution
 * in the MCP server. Uses a REAL {@link PrjXPConfig} (no mocks) per spec §5.1:
 * activeProject "alpha", projects [alpha, beta], stores {alpha (default), beta, gamma}.
 */
class ProjectRegistryTest {

    private static PrjXPEmbeddingStoreReference storeRef(String projectName, boolean isDefault) {
        PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        ref.setProjectName(projectName);
        ref.setDefault(isDefault);
        return ref;
    }

    private static ProjectDefinition project(String name) {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName(name);
        return pd;
    }

    private static PrjXPConfig config() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("alpha");
        cfg.setProjects(List.of(project("alpha"), project("beta")));
        cfg.setEmbeddingStores(List.of(
                storeRef("alpha", true),
                storeRef("beta", false),
                storeRef("gamma", false)));
        return cfg;
    }

    // ------------------------------------------------------------------ availableProjects

    @Test
    void availableProjectsListsStoreReferencesInConfigOrder() {
        ProjectRegistry registry = new ProjectRegistry(config());

        assertThat(registry.availableProjects()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void availableProjectsDeduplicatesRepeatedReferences() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef("alpha", true),
                storeRef("alpha", false),   // second reference to the same project
                storeRef("beta", false)));

        assertThat(new ProjectRegistry(cfg).availableProjects()).containsExactly("alpha", "beta");
    }

    @Test
    void availableProjectsSkipsNullAndBlankNames() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef(null, false),
                storeRef("   ", false),
                storeRef("alpha", true)));

        assertThat(new ProjectRegistry(cfg).availableProjects()).containsExactly("alpha");
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolveNullReturnsActiveProjectName() {
        assertThat(new ProjectRegistry(config()).resolve(null)).isEqualTo("alpha");
    }

    @Test
    void resolveBlankReturnsActiveProjectName() {
        assertThat(new ProjectRegistry(config()).resolve("   ")).isEqualTo("alpha");
    }

    @Test
    void resolveDefaultPassesThrough() {
        assertThat(new ProjectRegistry(config()).resolve("default")).isEqualTo("default");
    }

    @Test
    void resolveExplicitNamePassesThrough() {
        assertThat(new ProjectRegistry(config()).resolve("beta")).isEqualTo("beta");
    }

    // ------------------------------------------------------------------ isSearchable

    @Test
    void isSearchableTrueForEveryProjectWithStoreReference() {
        ProjectRegistry registry = new ProjectRegistry(config());

        assertThat(registry.isSearchable("alpha")).isTrue();
        assertThat(registry.isSearchable("beta")).isTrue();
        assertThat(registry.isSearchable("gamma")).isTrue();
    }

    @Test
    void isSearchableFalseForUnknownProject() {
        assertThat(new ProjectRegistry(config()).isSearchable("nope")).isFalse();
    }

    @Test
    void isSearchableFalseForNullAndBlank() {
        ProjectRegistry registry = new ProjectRegistry(config());

        assertThat(registry.isSearchable(null)).isFalse();
        assertThat(registry.isSearchable("  ")).isFalse();
    }

    @Test
    void isSearchableDefaultTrueWhenADefaultStoreReferenceExists() {
        assertThat(new ProjectRegistry(config()).isSearchable("default")).isTrue();   // alpha is the default store
    }

    @Test
    void isSearchableDefaultFalseWithoutDefaultStoreReference() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef("alpha", false),
                storeRef("beta", false)));

        assertThat(new ProjectRegistry(cfg).isSearchable("default")).isFalse();
    }

    // ------------------------------------------------------------------ ensureSearchable

    @Test
    void ensureSearchablePassesForKnownProject() {
        assertThatCode(() -> new ProjectRegistry(config()).ensureSearchable("beta")).doesNotThrowAnyException();
    }

    @Test
    void ensureSearchableThrowsForUnknownProjectListingAvailableProjects() {
        ProjectRegistry registry = new ProjectRegistry(config());

        assertThatThrownBy(() -> registry.ensureSearchable("nope"))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
                .hasMessageContaining("gamma");
    }

    @Test
    void ensureSearchableThrowsForDefaultWithoutDefaultStoreReference() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(storeRef("alpha", false)));

        assertThatThrownBy(() -> new ProjectRegistry(cfg).ensureSearchable("default"))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("default");
    }

    // ------------------------------------------------------------------ UnknownProjectException

    @Test
    void unknownProjectExceptionExposesProjectAndAvailable() {
        UnknownProjectException e = new UnknownProjectException("nope", List.of("alpha", "beta"));

        assertThat(e.getProject()).isEqualTo("nope");
        assertThat(e.getAvailable()).containsExactly("alpha", "beta");
        assertThat(e.getMessage()).contains("nope").contains("alpha").contains("beta");
    }

    @Test
    void unknownProjectExceptionWithEmptyAvailableListsNone() {
        assertThat(new UnknownProjectException("nope", List.of()).getMessage()).contains("(none)");
    }
}
