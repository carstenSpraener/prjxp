package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    private Path importDir;
    private PxChunkDaoProvider daoProvider;
    private LuceneEmbeddingStore luceneStore;
    private HubProjectRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        projectsRoot = tempDir.resolve("projects");
        Files.createDirectories(projectsRoot);
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);

        Path storeDir = tempDir.resolve("store");
        Files.createDirectories(storeDir);
        luceneStore = new LuceneEmbeddingStore(storeDir, 8);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        daoProvider = new PxChunkDaoProvider(List.of());

        HubProperties hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        hub.setImportDir(importDir.toString());

        PrjXPConfig cfg = new PrjXPConfig();
        ProjectDefinition alpha = new ProjectDefinition();
        alpha.setName("alpha");
        cfg.setActiveProject("alpha");   // getActiveProject() resolves the name against the projects list
        cfg.setProjects(List.of(alpha));

        registry = new HubProjectRegistry(cfg, hub, daoProvider, luceneStore, embeddingModel,
                new ProjectConfigFileParser());
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private Path projectDir(String name) throws Exception {
        Path dir = projectsRoot.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private Path liveDir(String name) throws Exception {
        Path dir = importDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private void indexChunkFor(String project) {
        luceneStore.addAll(
                List.of(Embedding.from(new float[8])),
                List.of(TextSegment.from("content of " + project,
                        Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, project)))));
    }

    // ------------------------------------------------------------------ registerProject

    @Test
    void registerProjectWithoutConfigFileUsesDefaults() throws Exception {
        Path dir = projectDir("alpha");

        registry.registerProject("alpha", dir);

        ProjectEntry entry = registry.entry("alpha").orElseThrow();
        assertThat(entry.getName()).isEqualTo("alpha");
        assertThat(entry.getRootDir()).isEqualTo(dir);
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);   // 2-arg registration = snapshot
        assertThat(entry.getDefinition().getName()).isEqualTo("alpha");
        // Phase 06 bug fix: the default relative rootDir "." is stamped absolute against the project dir
        assertThat(entry.getDefinition().getRootDir())
                .isEqualTo(dir.toAbsolutePath().normalize().toString());
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
        assertThat(entry.getDefinition().getRootDir()).isEqualTo("/custom/root");   // absolute passes through
        assertThat(entry.getDefinition().getJsonlFile()).isEqualTo("custom-chunks.jsonl");
        assertThat(entry.getDefinition().getTibedBatchSize()).isEqualTo(64);
    }

    @Test
    void registerProjectStampsRelativeRootDirAbsoluteAgainstProjectDir() throws Exception {
        Path dir = projectDir("gamma");
        Files.writeString(dir.resolve("prjxp.yaml"), "rootDir: src\n");

        registry.registerProject("gamma", dir);

        assertThat(registry.entry("gamma").orElseThrow().getDefinition().getRootDir())
                .isEqualTo(dir.toAbsolutePath().normalize().resolve("src").toString());
    }

    // ------------------------------------------------------------------ Phase 06: live registration + discovery

    @Test
    void registerLiveProjectRedirectsJsonlToHubManagedArea() throws Exception {
        Path dir = liveDir("foo");

        registry.registerProject("foo", dir, ProjectEntry.Kind.LIVE);

        ProjectEntry entry = registry.entry("foo").orElseThrow();
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.LIVE);
        // absolute path: resolvedJsonlFile() passes it through — the hub never writes into the live tree
        assertThat(entry.getDefinition().getJsonlFile()).isEqualTo("/data/chunks/foo.jsonl");
        assertThat(entry.getDefinition().resolvedJsonlFile()).isEqualTo("/data/chunks/foo.jsonl");
        assertThat(entry.getDefinition().getRootDir())
                .isEqualTo(dir.toAbsolutePath().normalize().toString());   // chunking walks the live tree
    }

    @Test
    void discoverRegistersSnapshotAndLiveProjectsFromBothRoots() throws Exception {
        projectDir("alpha");   // snapshot: plain directory under projectsRoot
        Path live = liveDir("livedir");
        Files.writeString(live.resolve("prjxp.yaml"), "name: fancy\n");   // live: marker file, yaml name

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).containsExactly("alpha", "fancy");   // sorted
        assertThat(registry.entry("alpha").orElseThrow().getKind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);
        assertThat(registry.entry("fancy").orElseThrow().getKind()).isEqualTo(ProjectEntry.Kind.LIVE);
        assertThat(registry.entry("fancy").orElseThrow().getRootDir()).isEqualTo(live);
    }

    @Test
    void discoverUsesDirectoryNameWhenYamlHasNoName() throws Exception {
        Path live = liveDir("plainname");
        Files.writeString(live.resolve("prjxp.yaml"), "tibedBatchSize: 16\n");

        registry.discoverProjects();

        assertThat(registry.entry("plainname")).isPresent();
    }

    @Test
    void discoverIgnoresImportDirEntriesWithoutMarker() throws Exception {
        liveDir("notaproject");   // no prjxp.yaml/yml inside

        assertThat(registry.discoverProjects()).isEmpty();
    }

    @Test
    void discoverFindsLiveProjectNestedInOrganizationalFolder() throws Exception {
        Path nested = importDir.resolve("teamA").resolve("projekt-x");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("prjxp.yaml"), "name: x\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).containsExactly("x");
        assertThat(registry.entry("x").orElseThrow().getRootDir()).isEqualTo(nested);   // the marker dir, not the folder
    }

    @Test
    void discoverFindsDeeplyNestedLiveProject() throws Exception {
        Path nested = importDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("prjxp.yaml"), "name: deep\n");

        assertThat(registry.discoverProjects()).containsExactly("deep");
    }

    @Test
    void nestedMarkerInsideLiveProjectIsNotDiscoveredSeparately() throws Exception {
        Path mono = importDir.resolve("mono");
        Files.createDirectories(mono);
        Files.writeString(mono.resolve("prjxp.yaml"), "name: mono\n");
        Path svc = Files.createDirectories(mono.resolve("svc-a"));   // nested marker — part of mono
        Files.writeString(svc.resolve("prjxp.yaml"), "name: svc-a\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).containsExactly("mono");   // outermost marker wins — no double registration
        assertThat(registry.entry("svc-a")).isEmpty();
    }

    @Test
    void nestedMarkerBecomesActiveWhenOuterMarkerDisappears() throws Exception {
        Path mono = importDir.resolve("mono");
        Files.createDirectories(mono);
        Files.writeString(mono.resolve("prjxp.yaml"), "name: mono\n");
        Path svc = Files.createDirectories(mono.resolve("svc-a"));   // shadowed while the outer marker exists
        Files.writeString(svc.resolve("prjxp.yaml"), "name: svc-a\n");
        registry.discoverProjects();

        Files.delete(mono.resolve("prjxp.yaml"));   // outer marker gone -> the nested one is now outermost

        assertThat(registry.discoverProjects()).containsExactly("svc-a");
    }

    @Test
    void liveNameCollidingWithExistingEntryIsSkipped() throws Exception {
        projectDir("foo");   // snapshot 'foo' registered first
        registry.discoverProjects();

        Path live = liveDir("bar");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");   // live dir wants the same name

        registry.discoverProjects();

        ProjectEntry entry = registry.entry("foo").orElseThrow();
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);   // untouched — no overwrite
        assertThat(entry.getRootDir()).isEqualTo(projectsRoot.resolve("foo"));
    }

    @Test
    void disappearedLiveProjectIsDeregisteredAndIndexWiped() throws Exception {
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();
        indexChunkFor("foo");

        Files.delete(live.resolve("prjxp.yaml"));   // marker removed (directory stays)

        List<String> rediscovered = registry.discoverProjects();

        assertThat(rediscovered).doesNotContain("foo");
        assertThat(registry.entry("foo")).isEmpty();
        assertThat(daoProvider.get("foo")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();   // scoped wipe
    }

    @Test
    void vanishedLiveDirectoryIsDeregisteredAndIndexWiped() throws Exception {
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();
        indexChunkFor("foo");

        Files.delete(live.resolve("prjxp.yaml"));
        Files.delete(live);   // the whole directory vanishes

        registry.discoverProjects();

        assertThat(registry.entry("foo")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();
    }

    @Test
    void missingImportDirSkipsLiveSyncEntirely() throws Exception {
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();

        Files.delete(live.resolve("prjxp.yaml"));
        Files.delete(live);
        Files.delete(importDir);   // volume glitch: the import dir itself is gone

        assertThatCode(() -> registry.discoverProjects()).doesNotThrowAnyException();
        assertThat(registry.entry("foo")).isPresent();   // NOT deregistered — no wipe on a glitch
    }

    @Test
    void disappearedSnapshotIsUnregisteredWithoutWipe() throws Exception {
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);
        indexChunkFor("alpha");

        Files.delete(dir);   // snapshot directory vanishes (unchanged behavior: unregister only)

        registry.discoverProjects();

        assertThat(registry.entry("alpha")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "alpha"))).isTrue();   // no wipe for snapshots
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
