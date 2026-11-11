package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.UnknownProjectException;
import de.spraener.prjxp.mcp.hub.source.GithubSource;
import de.spraener.prjxp.mcp.hub.source.LibrarySource;
import de.spraener.prjxp.mcp.hub.source.LiveSource;
import de.spraener.prjxp.mcp.hub.source.MarkerWalker;
import de.spraener.prjxp.mcp.hub.source.SnapshotSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
    private Path librariesRoot;
    private Path githubRoot;
    private PxChunkDaoProvider daoProvider;
    private LuceneEmbeddingStore luceneStore;
    private PrjXPConfig cfg;
    private HubProperties hub;
    private HubProjectRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        projectsRoot = tempDir.resolve("projects");
        Files.createDirectories(projectsRoot);
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        librariesRoot = tempDir.resolve("libraries");
        Files.createDirectories(librariesRoot);
        githubRoot = tempDir.resolve("github");
        Files.createDirectories(githubRoot);

        Path storeDir = tempDir.resolve("store");
        Files.createDirectories(storeDir);
        luceneStore = new LuceneEmbeddingStore(storeDir, 8);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        daoProvider = new PxChunkDaoProvider(List.of());

        hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        hub.setImportDir(importDir.toString());
        hub.setLibrariesRoot(librariesRoot.toString());
        hub.setGithubRoot(githubRoot.toString());

        cfg = new PrjXPConfig();
        ProjectDefinition alpha = new ProjectDefinition();
        alpha.setName("alpha");
        cfg.setActiveProject("alpha");   // getActiveProject() resolves the name against the projects list
        cfg.setProjects(List.of(alpha));

        registry = new HubProjectRegistry(cfg, hub, daoProvider, luceneStore, embeddingModel,
                new ProjectConfigFileParser(),
                new LiveUpdateScanner(hub, mock(VetoRegistry.class), luceneStore, new ObjectMapper()));
        MarkerWalker walker = new MarkerWalker(hub, new ProjectConfigFileParser());
        registry.setSources(List.of(new SnapshotSource(hub, walker), new LiveSource(hub, walker),
                new LibrarySource(hub, walker), new GithubSource(hub, walker)));
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
    void discoverStopsRecursionAtConfiguredMaxDepth() throws Exception {
        // Marker sits 4 levels below importDir — with scanMaxDepth=3 the walk must stop one level short.
        Path tooDeep = importDir.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(tooDeep);
        Files.writeString(tooDeep.resolve("prjxp.yaml"), "name: too-deep\n");

        HubProperties shallowProps = new HubProperties();
        shallowProps.setProjectsRoot(projectsRoot.toString());
        shallowProps.setImportDir(importDir.toString());
        shallowProps.setScanMaxDepth(3);
        HubProjectRegistry shallowRegistry = new HubProjectRegistry(
                new PrjXPConfig(), shallowProps, daoProvider, luceneStore,
                mock(EmbeddingModel.class), new ProjectConfigFileParser(),
                new LiveUpdateScanner(shallowProps, mock(VetoRegistry.class), luceneStore, new ObjectMapper()));
        MarkerWalker walker = new MarkerWalker(shallowProps, new ProjectConfigFileParser());
        shallowRegistry.setSources(List.of(new SnapshotSource(shallowProps, walker), new LiveSource(shallowProps, walker)));

        assertThat(shallowRegistry.discoverProjects()).doesNotContain("too-deep");
    }

    @Test
    void discoverFindsMarkerExactlyAtConfiguredMaxDepth() throws Exception {
        // Marker sits 3 levels below importDir — with scanMaxDepth=3 the walk must still reach it
        // (the marker check happens before the depth cutoff is applied).
        Path exact = importDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(exact);
        Files.writeString(exact.resolve("prjxp.yaml"), "name: exact\n");

        HubProperties shallowProps = new HubProperties();
        shallowProps.setProjectsRoot(projectsRoot.toString());
        shallowProps.setImportDir(importDir.toString());
        shallowProps.setScanMaxDepth(3);
        HubProjectRegistry shallowRegistry = new HubProjectRegistry(
                new PrjXPConfig(), shallowProps, daoProvider, luceneStore,
                mock(EmbeddingModel.class), new ProjectConfigFileParser(),
                new LiveUpdateScanner(shallowProps, mock(VetoRegistry.class), luceneStore, new ObjectMapper()));
        MarkerWalker walker = new MarkerWalker(shallowProps, new ProjectConfigFileParser());
        shallowRegistry.setSources(List.of(new SnapshotSource(shallowProps, walker), new LiveSource(shallowProps, walker)));

        assertThat(shallowRegistry.discoverProjects()).containsExactly("exact");
    }

    @Test
    void discoverSkipsConfiguredExcludeDirNamesEntirely() throws Exception {
        // A huge sibling subtree (e.g. an old SVN 'branches' checkout) sorts alphabetically before the
        // real project ('trunk') and has no marker of its own — it must be pruned, not walked.
        Path noise = importDir.resolve("branches").resolve("very").resolve("deep").resolve("legacy");
        Files.createDirectories(noise);
        // A marker planted inside the excluded tree must never surface — proves the prune happens
        // before the marker check, not just "no marker found here anyway".
        Files.writeString(noise.resolve("prjxp.yaml"), "name: ghost\n");

        Path real = importDir.resolve("trunk").resolve("ISA").resolve("isa");
        Files.createDirectories(real);
        Files.writeString(real.resolve("prjxp.yaml"), "name: isa\n");

        HubProperties propsWithExclude = new HubProperties();
        propsWithExclude.setProjectsRoot(projectsRoot.toString());
        propsWithExclude.setImportDir(importDir.toString());
        propsWithExclude.getScanExcludeDirNames().add("branches");
        HubProjectRegistry excludingRegistry = new HubProjectRegistry(
                new PrjXPConfig(), propsWithExclude, daoProvider, luceneStore,
                mock(EmbeddingModel.class), new ProjectConfigFileParser(),
                new LiveUpdateScanner(propsWithExclude, mock(VetoRegistry.class), luceneStore, new ObjectMapper()));
        MarkerWalker walker = new MarkerWalker(propsWithExclude, new ProjectConfigFileParser());
        excludingRegistry.setSources(List.of(new SnapshotSource(propsWithExclude, walker), new LiveSource(propsWithExclude, walker)));

        List<String> discovered = excludingRegistry.discoverProjects();

        assertThat(discovered).containsExactly("isa");
        assertThat(excludingRegistry.entry("ghost")).isEmpty();
    }

    @Test
    void discoverExcludeDirNameMatchIsCaseInsensitive() throws Exception {
        Path noise = importDir.resolve("BRANCHES");
        Files.createDirectories(noise);
        Files.writeString(noise.resolve("prjxp.yaml"), "name: ghost\n");

        HubProperties propsWithExclude = new HubProperties();
        propsWithExclude.setProjectsRoot(projectsRoot.toString());
        propsWithExclude.setImportDir(importDir.toString());
        propsWithExclude.getScanExcludeDirNames().add("branches");
        HubProjectRegistry excludingRegistry = new HubProjectRegistry(
                new PrjXPConfig(), propsWithExclude, daoProvider, luceneStore,
                mock(EmbeddingModel.class), new ProjectConfigFileParser(),
                new LiveUpdateScanner(propsWithExclude, mock(VetoRegistry.class), luceneStore, new ObjectMapper()));
        MarkerWalker walker = new MarkerWalker(propsWithExclude, new ProjectConfigFileParser());
        excludingRegistry.setSources(List.of(new SnapshotSource(propsWithExclude, walker), new LiveSource(propsWithExclude, walker)));

        assertThat(excludingRegistry.discoverProjects()).isEmpty();
    }

    // ------------------------------------------------------------------ .prjxp-exclude marker file

    @Test
    void discoverIgnoresDirectoryWithExcludeMarkerFile() throws Exception {
        Path excluded = liveDir("legacy-branch");
        Files.writeString(excluded.resolve("prjxp.yaml"), "name: ghost\n");
        Files.writeString(excluded.resolve(".prjxp-exclude"), "");

        assertThat(registry.discoverProjects()).isEmpty();
        assertThat(registry.entry("ghost")).isEmpty();
    }

    @Test
    void discoverIgnoresExcludeMarkerSubtreeEntirely() throws Exception {
        // .prjxp-exclude on a parent must prune the whole subtree — a marker deeper inside never surfaces.
        Path excludedParent = importDir.resolve("branches");
        Files.createDirectories(excludedParent);
        Files.writeString(excludedParent.resolve(".prjxp-exclude"), "");
        Path nestedProject = excludedParent.resolve("old-copy").resolve("isa");
        Files.createDirectories(nestedProject);
        Files.writeString(nestedProject.resolve("prjxp.yaml"), "name: ghost\n");

        Path real = importDir.resolve("trunk").resolve("isa");
        Files.createDirectories(real);
        Files.writeString(real.resolve("prjxp.yaml"), "name: isa\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).containsExactly("isa");
        assertThat(registry.entry("ghost")).isEmpty();
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

    // ------------------------------------------------------------------ Phase 05: source stamping

    @Test
    void registerProjectStampsSourceByKind() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.registerProject("foo", liveDir("foo"), ProjectEntry.Kind.LIVE);
        registry.registerProject("lib", projectDir("lib"), ProjectEntry.Kind.SNAPSHOT, "library");

        assertThat(registry.entry("alpha").orElseThrow().getSource()).isEqualTo("snapshot");
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("live");
        assertThat(registry.entry("lib").orElseThrow().getSource()).isEqualTo("library");
    }

    // ------------------------------------------------------------------ Phase 05: library discovery

    private Path libraryDir(String groupPath, String artifact, String version) throws Exception {
        Path dir = librariesRoot.resolve(groupPath).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        return dir;
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void discoverRegistersLibraryFromMarkerUnderLibrariesRoot() throws Exception {
        Path lib = libraryDir("com/example", "foo", "1.0");
        Files.writeString(lib.resolve("prjxp.yaml"), "name: foo-1.0\nmavenDependency: com.example:foo:1.0\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).contains("foo-1.0");
        ProjectEntry entry = registry.entry("foo-1.0").orElseThrow();
        assertThat(entry.getSource()).isEqualTo("library");
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);
        assertThat(entry.getRootDir()).isEqualTo(lib);
    }

    @Test
    void vanishedLibraryIsUnregisteredWithoutIndexWipe() throws Exception {
        Path lib = libraryDir("com/example", "foo", "1.0");
        Files.writeString(lib.resolve("prjxp.yaml"), "name: foo-1.0\n");
        registry.discoverProjects();
        indexChunkFor("foo-1.0");

        deleteRecursively(lib);   // the library directory vanishes

        registry.discoverProjects();

        assertThat(registry.entry("foo-1.0")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo-1.0"))).isTrue();   // no wipe (snapshot semantics)
    }

    @Test
    void snapshotVanishDoesNotAffectLibraryWithSameKind() throws Exception {
        // A library (SNAPSHOT kind, source "library") must survive a vanished projects root —
        // the source-based vanish filter only unregisters entries of the "snapshot" source.
        Path lib = libraryDir("com/example", "foo", "1.0");
        Files.writeString(lib.resolve("prjxp.yaml"), "name: foo-1.0\n");
        registry.discoverProjects();
        assertThat(registry.entry("foo-1.0")).isPresent();

        Files.delete(projectsRoot);   // the snapshot root vanishes (no snapshots registered)

        registry.discoverProjects();

        assertThat(registry.entry("foo-1.0")).isPresent();   // NOT unregistered by SnapshotSource
    }

    @Test
    void libraryVanishDoesNotAffectSnapshotWithSameKind() throws Exception {
        // Mirror: a snapshot (source "snapshot") must survive a vanished libraries root.
        registry.registerProject("alpha", projectDir("alpha"));
        assertThat(registry.entry("alpha").orElseThrow().getSource()).isEqualTo("snapshot");

        deleteRecursively(librariesRoot);   // the (optional) libraries root vanishes

        registry.discoverProjects();

        assertThat(registry.entry("alpha")).isPresent();   // NOT unregistered by LibrarySource
    }

    @Test
    void snapshotAndLibraryWithSameNameDoNotConfoundVanish() throws Exception {
        // Same name, different sources: the library is registered first; the colliding snapshot dir is skipped.
        Path lib = libraryDir("com/example", "foo", "1.0");
        Files.writeString(lib.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("library");

        Path snap = projectDir("foo");   // same name under projectsRoot — collides, must not overwrite the library
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("library");   // untouched

        Files.delete(snap);   // the colliding snapshot dir vanishes
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("library");   // still the library

        deleteRecursively(lib);   // now the library itself vanishes
        registry.discoverProjects();
        assertThat(registry.entry("foo")).isEmpty();   // unregistered by LibrarySource (unregister-only)
    }

    // ------------------------------------------------------------------ Phase 03: github discovery

    private Path githubCloneDir(String dirName) throws Exception {
        Path dir = githubRoot.resolve(dirName);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void discoverRegistersGithubCloneFromMarkerUnderGithubRoot() throws Exception {
        Path clone = githubCloneDir("bar");
        Files.writeString(clone.resolve("prjxp.yaml"),
                "name: bar\ngitUrl: \"https://github.com/foo/bar\"\n");

        List<String> discovered = registry.discoverProjects();

        assertThat(discovered).contains("bar");
        ProjectEntry entry = registry.entry("bar").orElseThrow();
        assertThat(entry.getSource()).isEqualTo("github");
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.SNAPSHOT);
        assertThat(entry.getRootDir()).isEqualTo(clone);
        assertThat(entry.getDefinition().getGitUrl()).isEqualTo("https://github.com/foo/bar");
    }

    @Test
    void vanishedGithubCloneIsUnregisteredAndIndexWiped() throws Exception {
        Path clone = githubCloneDir("bar");
        Files.writeString(clone.resolve("prjxp.yaml"), "name: bar\n");
        registry.discoverProjects();
        indexChunkFor("bar");

        deleteRecursively(clone);   // the clone vanishes (the github root is still listable)

        registry.discoverProjects();

        assertThat(registry.entry("bar")).isEmpty();
        assertThat(daoProvider.get("bar")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "bar"))).isFalse();   // scoped wipe
    }

    @Test
    void githubVanishDoesNotAffectSnapshotWithSameName() throws Exception {
        // Same name, different sources: the snapshot is registered first (source order); the colliding
        // github clone is skipped. Vanishing the github clone must not touch the snapshot.
        projectDir("foo");
        Path clone = githubCloneDir("foo-clone");
        Files.writeString(clone.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("snapshot");

        deleteRecursively(clone);   // the github clone vanishes

        registry.discoverProjects();

        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("snapshot");   // untouched
    }

    @Test
    void snapshotVanishDoesNotAffectGithubCloneWithSameName() throws Exception {
        // Mirror: the github clone is registered first; the colliding snapshot dir is skipped.
        // Vanishing the snapshot must not touch the github clone (source-based vanish filter).
        Path clone = githubCloneDir("foo-clone");
        Files.writeString(clone.resolve("prjxp.yaml"), "name: foo\n");
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("github");

        Path snap = projectDir("foo");   // same name under projectsRoot — collides, must not overwrite
        registry.discoverProjects();
        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("github");   // untouched

        Files.delete(snap);   // the colliding snapshot dir vanishes
        registry.discoverProjects();

        assertThat(registry.entry("foo").orElseThrow().getSource()).isEqualTo("github");   // still the github clone
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

    @Test
    void discoverWithNoConfiguredSourcesIsSafeNoOp() {
        registry.setSources(List.of());   // no sources (e.g. hub without any source beans)

        assertThat(registry.discoverProjects()).isEmpty();   // safe no-op — nothing to sync, nothing registered
    }

    // ------------------------------------------------------------------ interface contract (resolve / ensureSearchable)

    @Test
    void availableProjectsReturnsSortedKeys() throws Exception {
        registry.registerProject("zeta", projectDir("zeta"));
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.availableProjects()).containsExactly("alpha", "zeta");
    }

    @Test
    void resolveNullAndBlankThrowRequired() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("required")
                .hasMessageContaining("alpha");   // the available projects are listed
        assertThatThrownBy(() -> registry.resolve("  "))
                .isInstanceOf(UnknownProjectException.class);
    }

    @Test
    void resolveDefaultThrowsButExplicitNamesPassThrough() {
        assertThatThrownBy(() -> registry.resolve("default"))
                .isInstanceOf(UnknownProjectException.class);
        assertThat(registry.resolve("beta")).isEqualTo("beta");   // explicit names pass through
    }

    @Test
    void resolveNullThrowsRegardlessOfCwdFallback() throws Exception {
        // The reported bug (doc/Todos.md §4): the old code fell back to the synthetic "cwd"
        // active project (or the first READY project) for a null request. Now a null/blank
        // request is a hard error — the project is required, there is no implicit default.
        cfg.setProjects(List.of());
        cfg.setActiveProject("cwd");
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setStatus("alpha", ProjectStatus.READY, null);

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("required");
    }

    @Test
    void resolveNullAndDefaultThrowEvenWhenProjectsAreReady() throws Exception {
        cfg.setActiveProject("ghost");   // not in the static projects list -> getActiveProject() empty
        registry.registerProject("zeta", projectDir("zeta"));
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setStatus("zeta", ProjectStatus.READY, null);
        registry.setStatus("alpha", ProjectStatus.READY, null);

        assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(UnknownProjectException.class);
        assertThatThrownBy(() -> registry.resolve("default")).isInstanceOf(UnknownProjectException.class);
    }

    @Test
    void resolveNullThrowsEvenWhenActiveProjectIsRegistered() throws Exception {
        ProjectDefinition zeta = new ProjectDefinition();
        zeta.setName("zeta");
        cfg.setActiveProject("zeta");
        cfg.setProjects(List.of(zeta));
        registry.registerProject("alpha", projectDir("alpha"));
        registry.registerProject("zeta", projectDir("zeta"));
        registry.setStatus("alpha", ProjectStatus.READY, null);
        registry.setStatus("zeta", ProjectStatus.READY, null);

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("required");   // no implicit active-project default
    }

    @Test
    void resolveNullThrowsAndEnsureSearchableListsAvailable() throws Exception {
        cfg.setActiveProject("ghost");   // not resolvable -> no active project
        registry.registerProject("alpha", projectDir("alpha"));   // stays IMPORTING

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("alpha");   // the available projects are listed
        assertThatThrownBy(() -> registry.ensureSearchable(null))
                .isInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("alpha");
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

    // ------------------------------------------------------------------ Phase 06: extended ProjectInfo fields

    @Test
    void projectInfosIncludesKindAndStats() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        List<ProjectInfo> infos = registry.projectInfos();
        ProjectInfo info = infos.get(0);

        assertThat(info.name()).isEqualTo("alpha");
        assertThat(info.kind()).isEqualTo("SNAPSHOT");   // default kind for projectDir
        assertThat(info.chunkCount()).isGreaterThanOrEqualTo(0);
        assertThat(info.embeddingCount()).isEqualTo(info.chunkCount()); // 1:1 mapping
        assertThat(info.fileCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void projectInfosForLiveProjectShowsLiveKind() throws Exception {
        Path liveDir = importDir.resolve("my-live");
        Files.createDirectories(liveDir);
        Files.writeString(liveDir.resolve("prjxp.yaml"), "name: my-live\n");

        registry.discoverProjects();   // discovers live project from import dir
        List<ProjectInfo> infos = registry.projectInfos();

        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).kind()).isEqualTo("LIVE");
    }

    // ------------------------------------------------------------------ Phase 06: liveUpdate freshness block

    @Test
    void projectInfosForLiveProjectWithJsonlShowsIdleAndWatermark() throws Exception {
        hub.setLiveJsonlDir(tempDir.resolve("chunks").toString());
        Files.createDirectories(tempDir.resolve("chunks"));
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");

        registry.discoverProjects();   // registers the LIVE project
        Files.writeString(tempDir.resolve("chunks").resolve("foo.live-embedded.jsonl"),
                "{\"id\":\"c1\",\"file\":\"a.java\",\"project\":\"foo\",\"generatedAt\":42}\n");
        registry.refreshStats("foo");   // the watermark is cached — refresh to pick up the JSONL written above

        ProjectInfo info = registry.projectInfos().get(0);

        assertThat(info.kind()).isEqualTo("LIVE");
        assertThat(info.liveUpdate()).isNotNull();
        assertThat(info.liveUpdate().status()).isEqualTo("IDLE");
        assertThat(info.liveUpdate().lastGeneratedAt()).isEqualTo(42L);
    }

    @Test
    void projectInfosForSnapshotProjectHasNoLiveUpdateBlock() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        ProjectInfo info = registry.projectInfos().get(0);

        assertThat(info.kind()).isEqualTo("SNAPSHOT");
        assertThat(info.liveUpdate()).isNull();
    }

    @Test
    void projectInfosForLiveProjectWithLiveUpdateDisabledShowsDisabled() throws Exception {
        hub.setLiveJsonlDir(tempDir.resolve("chunks").toString());
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\nliveUpdate: false\n");

        registry.discoverProjects();

        ProjectInfo info = registry.projectInfos().get(0);

        assertThat(info.kind()).isEqualTo("LIVE");
        assertThat(info.liveUpdate()).isNotNull();
        assertThat(info.liveUpdate().status()).isEqualTo("DISABLED");
        assertThat(info.liveUpdate().lastGeneratedAt()).isNull();   // no JSONL yet
    }

    @Test
    void projectInfosForLiveProjectRunningShowsUpdatingWithProgress() throws Exception {
        hub.setLiveJsonlDir(tempDir.resolve("chunks").toString());
        Files.createDirectories(tempDir.resolve("chunks"));
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");

        registry.discoverProjects();   // registers the LIVE project
        ProjectEntry entry = registry.entry("foo").orElseThrow();
        entry.setEmbeddingRunning(true);
        entry.setLiveUpdateProgress(45);

        ProjectInfo info = registry.projectInfos().get(0);

        assertThat(info.liveUpdate()).isNotNull();
        assertThat(info.liveUpdate().status()).isEqualTo("UPDATING");
        assertThat(info.liveUpdate().progress()).isEqualTo(45);
    }

    @Test
    void projectInfosForLiveProjectQueuedButNotRunningShowsIdle() throws Exception {
        hub.setLiveJsonlDir(tempDir.resolve("chunks").toString());
        Files.createDirectories(tempDir.resolve("chunks"));
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");

        registry.discoverProjects();   // registers the LIVE project
        // queued (in-flight) but not yet running on the worker -> IDLE, not UPDATING
        registry.entry("foo").orElseThrow().setEmbeddingInFlight(true);

        ProjectInfo info = registry.projectInfos().get(0);

        assertThat(info.liveUpdate()).isNotNull();
        assertThat(info.liveUpdate().status()).isEqualTo("IDLE");
        assertThat(info.liveUpdate().progress()).isNull();
    }

    // ------------------------------------------------------------------ gitUrl (GitHub origin marker)

    @Test
    void projectInfosExposesGitUrlFromDefinition() throws Exception {
        Path dir = projectDir("ghproj");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: ghproj\ngitUrl: \"https://github.com/foo/bar\"\n");

        registry.registerProject("ghproj", dir);

        assertThat(registry.projectInfos().get(0).gitUrl()).isEqualTo("https://github.com/foo/bar");
    }

    @Test
    void projectInfosGitUrlNullWhenAbsent() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.projectInfos().get(0).gitUrl()).isNull();
    }

    // ------------------------------------------------------------------ gitTokenPresent (encrypted PAT marker)

    @Test
    void projectInfosGitTokenPresentTrueWhenGitUrlAndGitTokenSet() throws Exception {
        Path dir = projectDir("ghproj");
        Files.writeString(dir.resolve("prjxp.yaml"),
                "name: ghproj\ngitUrl: \"https://github.com/foo/bar\"\ngitToken: \"v1:aa:bb:cc\"\n");

        registry.registerProject("ghproj", dir);

        assertThat(registry.projectInfos().get(0).gitTokenPresent()).isTrue();
    }

    @Test
    void projectInfosGitTokenPresentFalseWhenOnlyGitUrl() throws Exception {
        Path dir = projectDir("ghproj");
        Files.writeString(dir.resolve("prjxp.yaml"),
                "name: ghproj\ngitUrl: \"https://github.com/foo/bar\"\n");

        registry.registerProject("ghproj", dir);

        assertThat(registry.projectInfos().get(0).gitTokenPresent()).isFalse();
    }

    @Test
    void projectInfosGitTokenPresentFalseWhenNeither() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.projectInfos().get(0).gitTokenPresent()).isFalse();
    }

    // ------------------------------------------------------------------ In-memory stats cache

    @Test
    void projectInfosServesCachedStatsUntilRefreshed() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        addEmbeddingForProject("alpha", "/a.java");
        addEmbeddingForProject("alpha", "/b.java");
        registry.refreshStats("alpha");   // cache: 2 chunks / 2 files

        addEmbeddingForProject("alpha", "/c.java");   // the index changes behind the cache's back

        ProjectInfo stale = registry.projectInfos().get(0);
        assertThat(stale.chunkCount()).isEqualTo(2);   // cached — no per-request index scan
        assertThat(stale.fileCount()).isEqualTo(2);

        registry.refreshStats("alpha");

        ProjectInfo fresh = registry.projectInfos().get(0);
        assertThat(fresh.chunkCount()).isEqualTo(3);
        assertThat(fresh.fileCount()).isEqualTo(3);
    }

    @Test
    void unregisterAndReRegisterRecomputesStats() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        addEmbeddingForProject("alpha", "/a.java");
        registry.refreshStats("alpha");   // cache: 1 chunk

        registry.unregisterProject("alpha");   // snapshot: no index wipe, but the cache entry is dropped
        addEmbeddingForProject("alpha", "/b.java");   // the index now holds 2 chunks

        registry.registerProject("alpha", projectDir("alpha"));   // re-registration recomputes

        assertThat(registry.projectInfos().get(0).chunkCount()).isEqualTo(2);   // not the stale 1
    }

    @Test
    void refreshStatsForUnknownProjectIsNoOp() {
        assertThatCode(() -> registry.refreshStats("ghost")).doesNotThrowAnyException();
    }

    @Test
    void scheduledStatsRefreshSkipsWhenDisabled() throws Exception {
        hub.setStatsRefreshIntervalMs(0);   // disabled via configuration
        registry.registerProject("alpha", projectDir("alpha"));
        addEmbeddingForProject("alpha", "/a.java");
        registry.refreshStats("alpha");   // cache: 1

        addEmbeddingForProject("alpha", "/b.java");   // the index now holds 2

        registry.scheduledStatsRefresh();   // disabled -> no refresh

        assertThat(registry.projectInfos().get(0).chunkCount()).isEqualTo(1);   // still the cached value
    }

    @Test
    void scheduledStatsRefreshSkipsWhenNotYetDue() throws Exception {
        hub.setStatsRefreshIntervalMs(60_000);   // the default 60 s
        registry.registerProject("alpha", projectDir("alpha"));
        addEmbeddingForProject("alpha", "/a.java");
        registry.refreshStats("alpha");   // cache: 1

        registry.scheduledStatsRefresh();   // first call: never refreshed -> due, sets the timestamp
        addEmbeddingForProject("alpha", "/b.java");   // the index now holds 2
        registry.scheduledStatsRefresh();   // called immediately -> not yet due (60 s)

        assertThat(registry.projectInfos().get(0).chunkCount()).isEqualTo(1);   // still the previous snapshot
    }

    @Test
    void scheduledStatsRefreshUpdatesWhenDue() throws Exception {
        hub.setStatsRefreshIntervalMs(1);   // 1 ms -> immediately due
        registry.registerProject("alpha", projectDir("alpha"));
        addEmbeddingForProject("alpha", "/a.java");
        registry.refreshStats("alpha");   // cache: 1

        addEmbeddingForProject("alpha", "/b.java");   // the index now holds 2

        registry.scheduledStatsRefresh();

        assertThat(registry.projectInfos().get(0).chunkCount()).isEqualTo(2);
    }

    @Test
    void countByProjectReturnsCorrectCount() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        // Add some embeddings to the lucene store for "alpha"
        addEmbeddingForProject("alpha", "/test.java");
        addEmbeddingForProject("alpha", "/other.java");

        long count = luceneStore.countByProject("alpha");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void countFilesByProjectReturnsDistinctFileCount() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        // Add 3 embeddings, but only 2 distinct files
        addEmbeddingForProject("alpha", "/test.java");
        addEmbeddingForProject("alpha", "/test.java");   // same file, should not count twice
        addEmbeddingForProject("alpha", "/other.java");

        int fileCount = luceneStore.countFilesByProject("alpha");
        assertThat(fileCount).isEqualTo(2);
    }

    @Test
    void countByProjectReturnsZeroForUnknownProject() {
        long count = luceneStore.countByProject("nonexistent");
        assertThat(count).isEqualTo(0);
    }

    /** Helper: add an embedding with project and file metadata to the lucene store. */
    private void addEmbeddingForProject(String project, String file) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_FILE, file);
        metadata.put(PxChunk.PXCHUNK_ID, "test-id-" + System.currentTimeMillis());

        TextSegment segment = new TextSegment("test content", metadata);
        Embedding embedding = new Embedding(new float[8]); // matches vectorDimension=8 in setUp

        luceneStore.add(embedding, segment);
    }

    // ------------------------------------------------------------------ Reindex progress tracking

    @Test
    void reindexStatusDefaultsToIdle() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.IDLE);
        assertThat(registry.getReindexProgress("alpha")).isZero();
    }

    @Test
    void reindexStatusUnknownProjectReturnsIdle() {
        assertThat(registry.getReindexStatus("nonexistent")).isEqualTo(ReindexStatus.IDLE);
        assertThat(registry.getReindexProgress("nonexistent")).isZero();
    }

    @Test
    void setReindexStatusAndProgress() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        registry.setReindexStatus("alpha", ReindexStatus.CHUNKING);
        registry.setReindexProgress("alpha", 25);

        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.CHUNKING);
        assertThat(registry.getReindexProgress("alpha")).isEqualTo(25);
    }

    @Test
    void resetReindexStatusClearsBothMaps() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.EMBEDDING);
        registry.setReindexProgress("alpha", 60);

        registry.resetReindexStatus("alpha");

        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.IDLE);
        assertThat(registry.getReindexProgress("alpha")).isZero();
    }

    @Test
    void projectInfosIncludesReindexStatus() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.CHUNKING);
        registry.setReindexProgress("alpha", 30);

        List<ProjectInfo> infos = registry.projectInfos();
        ProjectInfo info = infos.get(0);

        assertThat(info.reindexStatus()).isEqualTo("CHUNKING");
        assertThat(info.reindexProgress()).isEqualTo(30);
    }

    @Test
    void projectInfosReindexStatusDefaultsToIdle() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        List<ProjectInfo> infos = registry.projectInfos();
        ProjectInfo info = infos.get(0);

        assertThat(info.reindexStatus()).isEqualTo("IDLE");
        assertThat(info.reindexProgress()).isZero();
    }

    @Test
    void reindexStatusPerProjectIsIndependent() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.registerProject("beta", projectDir("beta"));

        registry.setReindexStatus("alpha", ReindexStatus.CHUNKING);
        registry.setReindexProgress("alpha", 40);

        assertThat(registry.getReindexStatus("beta")).isEqualTo(ReindexStatus.IDLE);
        assertThat(registry.getReindexProgress("beta")).isZero();
    }
}
