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
 * Phase 03: {@link StaticProjectRegistry} is the config-driven implementation of
 * {@link ProjectRegistry}, active when {@code prjxp.hub.enabled} is false or unset.
 * Uses a REAL {@link PrjXPConfig} (no mocks) per spec §5.1:
 * activeProject "alpha", projects [alpha, beta], stores {alpha (default), beta, gamma}.
 */
class StaticProjectRegistryTest {

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
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.availableProjects()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void availableProjectsDeduplicatesRepeatedReferences() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef("alpha", true),
                storeRef("alpha", false),   // second reference to the same project
                storeRef("beta", false)));

        assertThat(new StaticProjectRegistry(cfg).availableProjects()).containsExactly("alpha", "beta");
    }

    @Test
    void availableProjectsSkipsNullAndBlankNames() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef(null, false),
                storeRef("   ", false),
                storeRef("alpha", true)));

        assertThat(new StaticProjectRegistry(cfg).availableProjects()).containsExactly("alpha");
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolveNullReturnsActiveProjectName() {
        assertThat(new StaticProjectRegistry(config()).resolve(null)).isEqualTo("alpha");
    }

    @Test
    void resolveBlankReturnsActiveProjectName() {
        assertThat(new StaticProjectRegistry(config()).resolve("   ")).isEqualTo("alpha");
    }

    @Test
    void resolveDefaultPassesThrough() {
        assertThat(new StaticProjectRegistry(config()).resolve("default")).isEqualTo("default");
    }

    @Test
    void resolveExplicitNamePassesThrough() {
        assertThat(new StaticProjectRegistry(config()).resolve("beta")).isEqualTo("beta");
    }

    // ------------------------------------------------------------------ isSearchable

    @Test
    void isSearchableTrueForEveryProjectWithStoreReference() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.isSearchable("alpha")).isTrue();
        assertThat(registry.isSearchable("beta")).isTrue();
        assertThat(registry.isSearchable("gamma")).isTrue();
    }

    @Test
    void isSearchableFalseForUnknownProject() {
        assertThat(new StaticProjectRegistry(config()).isSearchable("nope")).isFalse();
    }

    @Test
    void isSearchableFalseForNullAndBlank() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.isSearchable(null)).isFalse();
        assertThat(registry.isSearchable("  ")).isFalse();
    }

    @Test
    void isSearchableDefaultTrueWhenADefaultStoreReferenceExists() {
        assertThat(new StaticProjectRegistry(config()).isSearchable("default")).isTrue();   // alpha is the default store
    }

    @Test
    void isSearchableDefaultFalseWithoutDefaultStoreReference() {
        PrjXPConfig cfg = config();
        cfg.setEmbeddingStores(List.of(
                storeRef("alpha", false),
                storeRef("beta", false)));

        assertThat(new StaticProjectRegistry(cfg).isSearchable("default")).isFalse();
    }

    // ------------------------------------------------------------------ ensureSearchable

    @Test
    void ensureSearchablePassesForKnownProject() {
        assertThatCode(() -> new StaticProjectRegistry(config()).ensureSearchable("beta")).doesNotThrowAnyException();
    }

    @Test
    void ensureSearchableThrowsForUnknownProjectListingAvailableProjects() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

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

        assertThatThrownBy(() -> new StaticProjectRegistry(cfg).ensureSearchable("default"))
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

    // ------------------------------------------------------------------ statusOf / projectInfos (Phase 03)

    @Test
    void statusOfReadyForProjectWithStoreReference() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.statusOf("alpha")).isEqualTo("READY");
        assertThat(registry.statusOf("beta")).isEqualTo("READY");
    }

    @Test
    void statusOfUnknownForProjectWithoutStoreReference() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.statusOf("nope")).isEqualTo("UNKNOWN");
    }

    @Test
    void statusOfUnknownForNullAndBlank() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        assertThat(registry.statusOf(null)).isEqualTo("UNKNOWN");
        assertThat(registry.statusOf("  ")).isEqualTo("UNKNOWN");
    }

    @Test
    void projectInfosMapsAvailableProjectsToReady() {
        StaticProjectRegistry registry = new StaticProjectRegistry(config());

        List<ProjectInfo> infos = registry.projectInfos();
        assertThat(infos).hasSize(3);

        ProjectInfo alpha = infos.get(0);
        assertThat(alpha.name()).isEqualTo("alpha");
        assertThat(alpha.status()).isEqualTo("READY");
        assertThat(alpha.lastError()).isNull();

        assertThat(infos).containsExactly(
                new ProjectInfo("alpha", "READY", null),
                new ProjectInfo("beta", "READY", null),
                new ProjectInfo("gamma", "READY", null));
    }
}
