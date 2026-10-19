package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.docpipe.DocPipeManifestWriter;
import de.spraener.prjxp.docpipe.DocPipeRunResult;
import de.spraener.prjxp.docpipe.DocPipeRunner;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.EmbeddingExportService;
import de.spraener.prjxp.tibed.EmbeddingService;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the full DocPipePipelineIntegration chain:
 * root run → DocPipe inline (opt-in via .dp/) → manifest → retrigger run → document embedding → READY.
 * No LLM: {@link ContentCreationService} is mocked (writes the document + records the output).
 * <p>
 * Setup mirrors {@link PipelineOrchestratorTest}: real registry + real 8-dim Lucene store; the
 * DocPipe chain is REAL (job creation, runner, manifest writer) with only the content creation mocked.
 */
class DocPipePipelineIntegrationTest {

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

    private PxLogService logService;
    private DotDPFilesService dpFilesService;
    private JobCreationService jobCreationService;
    private DocPipeManifestWriter manifestWriter;
    private ContentCreationService contentCreationService;
    private DocPipeRunner docPipeRunner;
    private DocPipePipelineListener listener;

    private PipelineOrchestrator orchestrator;

    private Path projectDir;

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

        // Real DocPipe chain (no LLM): real job creation + manifest writer, mocked content creation
        logService = mock(PxLogService.class);
        dpFilesService = new DotDPFilesService();
        jobCreationService = new JobCreationService(cfg, objMapper, dpFilesService, logService);
        manifestWriter = new DocPipeManifestWriter(objMapper);
        contentCreationService = mock(ContentCreationService.class);
        docPipeRunner = new DocPipeRunner(jobCreationService, contentCreationService,
                mock(ModelConfigLoader.class), dpFilesService, logService);
        ReflectionTestUtils.setField(docPipeRunner, "maxThreads", 2);
        docPipeRunner.setManifestWriter(manifestWriter);

        listener = new DocPipePipelineListener(jobCreationService, docPipeRunner, registry, objMapper);

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService,
                compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.setEmbeddingService(embeddingService);
        orchestrator.setPipelineListeners(List.of(listener));

        // Project fixture: empty prjxp.yaml + .dp/documents.json (the DocPipe opt-in)
        projectDir = Files.createDirectories(projectsRoot.resolve("p1"));
        Files.writeString(projectDir.resolve("prjxp.yaml"), "");
        Path dpDir = Files.createDirectories(projectDir.resolve(".dp"));
        Files.writeString(dpDir.resolve("documents.json"),
                "[ { \"outputFile\": \"docs/a.md\", \"stereotype\": \"doc-writer\" } ]");
        registry.registerProject("p1", projectDir);

        // The root run must succeed: the export writes one valid embedded record
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("code-1");
                cc.setContent("code content");
            });
            EmbeddedChunkRecord rec = EmbeddedChunkRecord.from(c, v);
            Files.writeString(out, new ObjectMapper().writeValueAsString(rec) + "\n");
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        // DocPipe content creation (mocked, no LLM): writes the document + records the output
        doAnswer(inv -> {
            DocPipeRunResult r = inv.getArgument(1);
            Path docsDir = Files.createDirectories(projectDir.resolve("docs"));
            Files.writeString(docsDir.resolve("a.md"), "# A\ncontent");
            r.recordOutput(projectDir.resolve("docs/a.md").toString());
            return null;
        }).when(contentCreationService).createContent(any(ContentCreationTask.class), any(DocPipeRunResult.class));

        // The document-embedding run: the chunker produces one fake doc chunk
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(
                PxChunk.create(c -> c.setId("docs/a.md:01.01:01"), c -> c.setContent("# A"))));
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        luceneStore.close();
    }

    @Test
    void fullChainRootRunDocPipeRetriggerReady() throws Exception {
        orchestrator.enqueueForced("p1");

        long deadline = System.currentTimeMillis() + 30_000;
        ProjectStatus actual = null;
        while (System.currentTimeMillis() < deadline) {
            var entry = registry.entry("p1");
            if (entry.isPresent()) {
                actual = entry.get().getStatus();
                if (actual == ProjectStatus.READY) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        assertThat(actual).as("final lifecycle status").isEqualTo(ProjectStatus.READY);

        // 1. DocPipe ran inline during the root run: the document exists
        assertThat(projectDir.resolve("docs/a.md")).exists();

        // 2. The manifest exists and contains the document (root-relative)
        Path manifest = projectDir.resolve(DocPipeManifestWriter.MANIFEST_RELATIVE_PATH);
        assertThat(manifest).exists();
        assertThat(new ObjectMapper().readValue(manifest.toFile(), String[].class)).containsExactly("docs/a.md");

        // 3. The document-embedding run embedded the stamped chunk
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService, times(1)).embedChunks(eq(luceneStore), eq("p1"), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        PxChunk embedded = captor.getValue().get(0);
        assertThat(embedded.getProject()).isEqualTo("p1");
        assertThat(embedded.getMetadata().get(DocumentSource.DOCPIPE_GENERATED)).isEqualTo("true");

        // 4. The retrigger run completed: progress 100 + markdown whitelisted for the doc chunking
        assertThat(registry.getReindexProgress("p1")).isEqualTo(100);
        ArgumentCaptor<ProjectDefinition> defCaptor = ArgumentCaptor.forClass(ProjectDefinition.class);
        verify(chunkProcess).executeForFiles(defCaptor.capture(), any());
        assertThat(defCaptor.getValue().getChunoWhiteList()).contains("md");
    }
}
