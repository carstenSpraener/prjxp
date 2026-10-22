package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.EmbeddingExportService;
import de.spraener.prjxp.tibed.EmbeddingService;
import de.spraener.prjxp.tibed.PxChunk2TextSegmentConverter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the document-embedding run ({@link PipelineOrchestrator#scheduleEmbedding}): the source's
 * documents are chunked (markdown whitelisted), a file-scoped reset removes only the previously
 * generated document chunks of the re-embedded files (code chunks and other documents are
 * untouched), and the new chunks are stamped + embedded additively.
 * <p>
 * Setup mirrors {@link PipelineOrchestratorTest}: real registry + real 8-dim Lucene store, mocked
 * ChunkProcess/EmbeddingExportService/EmbeddingService. The retrigger is triggered by setting the
 * orchestrator's {@code currentRun} to a root run (the listener→scheduleRetrigger mechanism itself
 * is covered by the Phase 01 dispatch tests).
 */
class DocumentEmbeddingRunTest {

    @TempDir
    Path tempRoot;

    private HubProperties hub;
    private PrjXPConfig cfg;
    private PxChunkDaoProvider provider;
    private LuceneEmbeddingStore luceneStore;
    private EmbeddingModel embeddingModel;
    private ProjectConfigFileParser parser;
    private HubProjectRegistry registry;

    private ChunkProcess chunkProcess;
    private EmbeddingExportService exportService;
    private EmbeddingCompatibilityChecker compatibilityChecker;
    private EmbeddingService embeddingService;
    private ObjectMapper objMapper;

    private PipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        Path projectsRoot = Files.createDirectories(tempRoot.resolve("projects"));
        Path indexDir = Files.createDirectories(tempRoot.resolve("index"));

        hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        cfg = new PrjXPConfig();
        provider = new PxChunkDaoProvider(List.of());
        luceneStore = new LuceneEmbeddingStore(indexDir, 8);
        embeddingModel = mock(EmbeddingModel.class);
        parser = new ProjectConfigFileParser();
        registry = new HubProjectRegistry(cfg, hub, provider, luceneStore, embeddingModel, parser);

        chunkProcess = mock(ChunkProcess.class);
        exportService = mock(EmbeddingExportService.class);
        compatibilityChecker = mock(EmbeddingCompatibilityChecker.class);
        embeddingService = mock(EmbeddingService.class);
        objMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        luceneStore.close();
    }

    private PipelineOrchestrator createOrchestrator() {
        PipelineOrchestrator o = new PipelineOrchestrator(registry, chunkProcess, exportService,
                compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        o.setEmbeddingService(embeddingService);
        return o;
    }

    private Path registerProject(String name) throws Exception {
        Path dir = Files.createDirectories(tempRoot.resolve("projects").resolve(name));
        Files.writeString(dir.resolve("prjxp.yaml"), "");   // empty -> all defaults (whitelist "java,ts")
        registry.registerProject(name, dir);
        return dir;
    }

    private void awaitStatus(String name, ProjectStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ProjectStatus actual = null;
        while (System.currentTimeMillis() < deadline) {
            var entry = registry.entry(name);
            if (entry.isPresent()) {
                actual = entry.get().getStatus();
                if (actual == expected) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertThat(actual).as("lifecycle status of '%s'", name).isEqualTo(expected);
    }

    /** Creates a real file under the project root and returns a source yielding its root-relative path. */
    private DocumentSource sourceFor(Path projectDir, String relativePath) throws Exception {
        Path abs = projectDir.resolve(relativePath);
        Files.createDirectories(abs.getParent());
        Files.writeString(abs, "content of " + relativePath);
        return () -> Stream.of(Path.of(relativePath));
    }

    /** Seeds the real store with one chunk (optionally stamped as docpipe-generated, optionally with a file). */
    private void seed(String id, String project, boolean docMarker, String file) {
        PxChunk c = PxChunk.create(cc -> {
            cc.setId(id);
            cc.setProject(project);
            cc.setContent("content of " + id);
            if (file != null) {
                cc.setFile(file);
            }
        });
        if (docMarker) {
            c.getMetadata().put(DocumentSource.DOCPIPE_GENERATED, "true");
        }
        TextSegment seg = PxChunk2TextSegmentConverter.convert(c);
        luceneStore.addAll(List.of(Embedding.from(new float[8])), List.of(seg));
    }

    /** Makes the mocked embedChunks actually write to the real store (simulating the real embedder). */
    private void mockEmbeddingWritesToStore() {
        doAnswer(inv -> {
            List<PxChunk> chunks = inv.getArgument(2);
            List<Embedding> embeddings = chunks.stream().map(c -> Embedding.from(new float[8])).toList();
            List<TextSegment> segments = chunks.stream().map(PxChunk2TextSegmentConverter::convert).toList();
            luceneStore.addAll(embeddings, segments);
            return null;
        }).when(embeddingService).embedChunks(any(), anyString(), any());
    }

    /**
     * Sets the orchestrator's currentRun to a root run for the project (so scheduleEmbedding's
     * scheduleRetrigger accepts it), schedules the document-embedding retrigger, and awaits READY.
     */
    private void runDocumentEmbedding(String name, DocumentSource source) throws Exception {
        ReflectionTestUtils.setField(orchestrator, "currentRun", PipelineRun.root(name));
        orchestrator.scheduleEmbedding(name, source);
        awaitStatus(name, ProjectStatus.READY);
    }

    @Test
    void scheduleEmbeddingRunsDocumentPipeline() throws Exception {
        Path dir = registerProject("p1");
        Path docsDir = Files.createDirectories(dir.resolve("docs"));
        Files.writeString(docsDir.resolve("a.md"), "# Doc A");
        Path manifestDir = Files.createDirectories(dir.resolve(".prjxp-data").resolve("docpipe"));
        Files.writeString(manifestDir.resolve("manifest.json"), "[\"docs/a.md\"]");

        String[] manifestPaths = new ObjectMapper().readValue(
                manifestDir.resolve("manifest.json").toFile(), String[].class);
        DocumentSource source = () -> Arrays.stream(manifestPaths).map(Path::of);

        PxChunk docChunk = PxChunk.create(c -> c.setId("doc-1"), c -> c.setContent("doc content"));
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(docChunk));

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(1)).embedChunks(eq(luceneStore), eq("p1"), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        PxChunk embedded = captor.getValue().get(0);
        assertThat(embedded.getProject()).isEqualTo("p1");
        assertThat(embedded.getMetadata().get(DocumentSource.DOCPIPE_GENERATED)).isEqualTo("true");
        assertThat(registry.entry("p1").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void fileScopedResetRemovesOnlyDocChunksOfReembeddedFiles() throws Exception {
        Path dir = registerProject("p1");
        seed("code-1", "p1", false, null);
        seed("old-doc-1", "p1", true, "docs/x.md");
        assertThat(luceneStore.countByProject("p1")).isEqualTo(2);

        PxChunk newDoc = PxChunk.create(c -> c.setId("new-doc-1"), c -> c.setContent("new doc"),
                c -> c.setFile("docs/x.md"));
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(newDoc));
        mockEmbeddingWritesToStore();

        DocumentSource source = sourceFor(dir, "docs/x.md");

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        // file-scoped reset: old doc chunk of the same file wiped, code chunk intact, new doc chunk added (2 -> 2)
        assertThat(luceneStore.countByProject("p1")).isEqualTo(2);
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "code-1"))).isTrue();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-doc-1"))).isFalse();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "new-doc-1"))).isTrue();
    }

    @Test
    void fileScopedResetKeepsUnchangedDocuments() throws Exception {
        Path dir = registerProject("p1");
        seed("code-1", "p1", false, null);
        seed("old-doc-a", "p1", true, "docs/a.md");   // re-embedded in this run
        seed("old-doc-b", "p1", true, "docs/b.md");   // unchanged document (not in the manifest)
        assertThat(luceneStore.countByProject("p1")).isEqualTo(3);

        PxChunk newDocA = PxChunk.create(c -> c.setId("new-doc-a"), c -> c.setContent("new a"),
                c -> c.setFile("docs/a.md"));
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(newDocA));
        mockEmbeddingWritesToStore();

        DocumentSource source = sourceFor(dir, "docs/a.md");   // only a.md is (re)generated

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        // regression: the unchanged document b.md must survive (the old doc-wide reset wiped it)
        assertThat(luceneStore.countByProject("p1")).isEqualTo(3);
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "code-1"))).isTrue();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-doc-a"))).isFalse();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "new-doc-a"))).isTrue();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-doc-b"))).isTrue();
    }

    @Test
    void emptySourceNoEmbedding() throws Exception {
        registerProject("p1");
        DocumentSource source = () -> Stream.empty();

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        verify(embeddingService, never()).embedChunks(any(), anyString(), any());
        assertThat(registry.entry("p1").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void missingFilesSkipped() throws Exception {
        registerProject("p1");
        DocumentSource source = () -> Stream.of(Path.of("docs/missing.md"));   // does not exist on disk

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        verify(embeddingService, never()).embedChunks(any(), anyString(), any());
        assertThat(registry.entry("p1").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void whitelistGetsMdAppended() throws Exception {
        Path dir = registerProject("p1");   // default whitelist "java,ts"
        DocumentSource source = sourceFor(dir, "docs/x.md");
        PxChunk docChunk = PxChunk.create(c -> c.setId("doc-1"), c -> c.setContent("doc"));
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(docChunk));

        orchestrator = createOrchestrator();
        runDocumentEmbedding("p1", source);

        ArgumentCaptor<ProjectDefinition> defCaptor = ArgumentCaptor.forClass(ProjectDefinition.class);
        verify(chunkProcess).executeForFiles(defCaptor.capture(), any());
        assertThat(defCaptor.getValue().getChunoWhiteList()).contains("md");
        // the registry-owned definition is NOT mutated
        assertThat(registry.definitionOf("p1").getChunoWhiteList()).isEqualTo("java,ts");
    }
}
