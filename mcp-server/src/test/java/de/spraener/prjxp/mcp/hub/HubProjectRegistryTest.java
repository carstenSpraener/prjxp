package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Phase 03: {@link HubProjectRegistry} is the directory-driven registry of hub mode.
 * Constructed directly (no Spring context) with a REAL {@link LuceneEmbeddingStore},
 * a mocked {@link EmbeddingModel} and real {@link PxChunkDaoProvider}/{@link HubProperties}/parser.
 */
class HubProjectRegistryTest {

    @TempDir
    Path tempDir;

    private Path projectsRoot;
    private PxChunkDaoProvider daoProvider;
    private HubProjectRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        projectsRoot = tempDir.resolve("projects");
        Files.createDirectories(projectsRoot);

        Path storeDir = tempDir.resolve("store");
        Files.createDirectories(storeDir);
        LuceneEmbeddingStore luceneStore = new LuceneEmbeddingStore(storeDir, 8);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        daoProvider = new PxChunkDaoProvider(List.of());

        HubProperties hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());

        PrjXPConfig cfg = new PrjXPConfig();
        ProjectDefinition alpha = new ProjectDefinition();
        alpha.setName("alpha");
        cfg.setActiveProject("alpha");   // getActiveProject() resolves the name against the projects list
        cfg.setProjects(List.of(alpha));

        registry = new HubProjectRegistry(cfg, hub, daoProvider, luceneStore, embeddingModel,
                new ProjectConfigFileParser());
    }

    private Path projectDir(String name) throws Exception {
        Path dir = projectsRoot.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    // ------------------------------------------------------------------ registerProject

    @Test
    void registerProjectWithoutConfigFileUsesDefaults() throws Exception {
        Path dir = projectDir("alpha");

        registry.registerProject("alpha", dir);

        ProjectEntry entry = registry.entry("alpha").orElseThrow();
        assertThat(entry.getName()).isEqualTo("alpha");
        assertThat(entry.getRootDir()).isEqualTo(dir);
        assertThat(entry.getDefinition().getName()).isEqualTo("alpha");
        assertThat(entry.getDefinition().getRootDir()).isEqualTo(".");
        assertThat(entry.getDefinition().getJsonlFile()).isEqualTo("px-chunks.jsonl");
        assertThat(entry.getStoreRef().getProjectName()).isEqualTo("alpha");
        assertThat(entry.getStatus()).isEqualTo(ProjectStatus.IMPORTING);
        assertThat(daoProvider.get("alpha")).isPresent();
    }

    @Test
    void registerProjectWithConfigFilePicksUpValues() throws Exception {
        Path dir = projectDir("beta");
        Files.writeString(dir.resolve("prjxp.yaml"), """
                rootDir: /custom/root
                jsonlFile: custom-chunks.jsonl
                tibedBatchSize: 64
                """);

        registry.registerProject("beta", dir);

        ProjectEntry entry = registry.entry("beta").orElseThrow();
        assertThat(entry.getDefinition().getName()).isEqualTo("beta");   // no name key in yaml -> defaultName
        assertThat(entry.getDefinition().getRootDir()).isEqualTo("/custom/root");
        assertThat(entry.getDefinition().getJsonlFile()).isEqualTo("custom-chunks.jsonl");
        assertThat(entry.getDefinition().getTibedBatchSize()).isEqualTo(64);
    }

    // ------------------------------------------------------------------ setStatus / searchability

    @Test
    void projectIsNotSearchableWhileImporting() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.isSearchable("alpha")).isFalse();
    }

    @Test
    void setStatusReadyMakesProjectSearchable() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        registry.setStatus("alpha", ProjectStatus.READY, null);

        assertThat(registry.isSearchable("alpha")).isTrue();
        assertThat(registry.statusOf("alpha")).isEqualTo("READY");

        List<ProjectInfo> infos = registry.projectInfos();
        assertThat(infos).hasSize(1);
        ProjectInfo info = infos.get(0);
        assertThat(info.name()).isEqualTo("alpha");
        assertThat(info.status()).isEqualTo("READY");
        assertThat(info.lastError()).isNull();
    }

    @Test
    void failedStatusCarriesLastErrorInProjectInfos() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        registry.setStatus("alpha", ProjectStatus.FAILED, "tar exploded");

        assertThat(registry.isSearchable("alpha")).isFalse();
        ProjectInfo info = registry.projectInfos().get(0);
        assertThat(info.status()).isEqualTo("FAILED");
        assertThat(info.lastError()).isEqualTo("tar exploded");
    }

    @Test
    void setStatusForUnknownProjectIsNoOp() {
        assertThatCode(() -> registry.setStatus("ghost", ProjectStatus.READY, null))
                .doesNotThrowAnyException();
        assertThat(registry.entry("ghost")).isEmpty();
    }

    // ------------------------------------------------------------------ unregisterProject

    @Test
    void unregisterProjectRemovesEntryAndDao() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        registry.unregisterProject("alpha");

        assertThat(registry.entry("alpha")).isEmpty();
        assertThat(daoProvider.get("alpha")).isEmpty();
    }

    @Test
    void unregisterUnknownProjectIsNoOp() {
        assertThatCode(() -> registry.unregisterProject("ghost")).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ discoverProjects

    @Test
    void discoverRegistersNewProjectDirs() throws Exception {
        projectDir("alpha");
        Path beta = projectDir("beta");
        Files.writeString(beta.resolve("prjxp.yaml"), "tibedBatchSize: 16\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).containsExactly("alpha", "beta");   // sorted
        assertThat(registry.entry("alpha")).isPresent();
        assertThat(registry.entry("beta").orElseThrow().getDefinition().getTibedBatchSize()).isEqualTo(16);
        assertThat(daoProvider.get("alpha")).isPresent();
        assertThat(daoProvider.get("beta")).isPresent();
    }

    @Test
    void discoverUnregistersVanishedProjectDirs() throws Exception {
        projectDir("alpha");
        Path beta = projectDir("beta");

        registry.discoverProjects();
        assertThat(registry.entry("beta")).isPresent();

        Files.delete(beta);   // the project directory vanishes

        List<String> rediscovered = registry.discoverProjects();

        assertThat(rediscovered).containsExactly("alpha");
        assertThat(registry.entry("beta")).isEmpty();
        assertThat(daoProvider.get("beta")).isEmpty();   // its DAO is gone from the provider
    }

    @Test
    void discoverWithMissingRootReturnsEmptyAndUnregistersAll() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        Files.delete(projectsRoot.resolve("alpha"));   // remove the only project dir
        Files.delete(projectsRoot);                    // ...then the root itself vanishes

        assertThat(registry.discoverProjects()).isEmpty();
        assertThat(registry.entry("alpha")).isEmpty();
    }

    @Test
    void discoverIgnoresFilesInRoot() throws Exception {
        Files.writeString(projectsRoot.resolve("stray.txt"), "not a project");
        projectDir("alpha");

        assertThat(registry.discoverProjects()).containsExactly("alpha");
    }

    // ------------------------------------------------------------------ interface contract (resolve / ensureSearchable)

    @Test
    void availableProjectsReturnsSortedKeys() throws Exception {
        registry.registerProject("zeta", projectDir("zeta"));
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.availableProjects()).containsExactly("alpha", "zeta");
    }

    @Test
    void resolveNullAndBlankReturnActiveProjectName() {
        assertThat(registry.resolve(null)).isEqualTo("alpha");   // active project from PrjXPConfig
        assertThat(registry.resolve("  ")).isEqualTo("alpha");
    }

    @Test
    void resolveDefaultAndExplicitNamesPassThrough() {
        assertThat(registry.resolve("default")).isEqualTo("default");
        assertThat(registry.resolve("beta")).isEqualTo("beta");
    }

    @Test
    void ensureSearchablePassesForReadyProject() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setStatus("alpha", ProjectStatus.READY, null);

        assertThatCode(() -> registry.ensureSearchable("alpha")).doesNotThrowAnyException();
    }

    @Test
    void ensureSearchableThrowsForNonReadyProjectListingAvailable() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // still IMPORTING

        assertThatThrownBy(() -> registry.ensureSearchable("alpha"))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("alpha");
    }

    @Test
    void statusOfUnknownForMissingOrBlankName() {
        assertThat(registry.statusOf("nope")).isEqualTo("UNKNOWN");
        assertThat(registry.statusOf(null)).isEqualTo("UNKNOWN");
        assertThat(registry.statusOf(" ")).isEqualTo("UNKNOWN");
    }

    @Test
    void isSearchableFalseForMissingOrBlankName() {
        assertThat(registry.isSearchable(null)).isFalse();
        assertThat(registry.isSearchable(" ")).isFalse();
        assertThat(registry.isSearchable("nope")).isFalse();
    }

    @Test
    void definitionOfReturnsDefinitionOrNullForUnknown() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.definitionOf("alpha").getName()).isEqualTo("alpha");
        assertThat(registry.definitionOf("nope")).isNull();
    }

    @Test
    void projectInfosSortedByName() throws Exception {
        registry.registerProject("zeta", projectDir("zeta"));
        registry.registerProject("alpha", projectDir("alpha"));

        List<ProjectInfo> infos = registry.projectInfos();
        assertThat(infos).extracting(ProjectInfo::name).containsExactly("alpha", "zeta");
    }
}
