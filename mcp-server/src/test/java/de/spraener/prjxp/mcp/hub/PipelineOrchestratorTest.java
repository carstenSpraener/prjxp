package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.EmbeddingExecutor;
import de.spraener.prjxp.tibed.EmbeddingExportService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the hub pipeline with a REAL registry + real Lucene index (8-dim) and
 * mocked ChunkProcess/EmbeddingExportService. The export stub writes a valid
 * EmbeddedChunkRecord JSONL file so the real import path (real ObjectMapper +
 * real store) is exercised. Async completion is awaited via latches / status polling.
 */
class PipelineOrchestratorTest {

    @TempDir
    Path tempRoot;

    private HubProperties hub;
    private PrjXPConfig cfg;
    private PxChunkDaoProvider provider;
    private LuceneEmbeddingStore luceneStore;
    private EmbeddingModel embeddingModel;   // mocked — never invoked (chunk/export are mocked)
    private ProjectConfigFileParser parser;
    private HubProjectRegistry registry;

    private ChunkProcess chunkProcess;       // mocked
    private EmbeddingExportService exportService;  // mocked — stub writes a valid embedded JSONL file
    private EmbeddingCompatibilityChecker compatibilityChecker;  // mocked
    private ObjectMapper objMapper;          // REAL — importEmbeddedFile deserializes the temp file
    private EmbeddingExecutor embedder;      // mocked — compute-only embed() (live updates)
    private LiveUpdateScanner scanner;       // mocked — read-only change detection (live updates)

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
        scanner = mock(LiveUpdateScanner.class);
        registry = new HubProjectRegistry(cfg, hub, provider, luceneStore, embeddingModel, parser, scanner);

        chunkProcess = mock(ChunkProcess.class);
        exportService = mock(EmbeddingExportService.class);
        compatibilityChecker = mock(EmbeddingCompatibilityChecker.class);
        objMapper = new ObjectMapper();
        embedder = mock(EmbeddingExecutor.class);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private Path projectDir(String name) throws Exception {
        return Files.createDirectories(tempRoot.resolve("projects").resolve(name));
    }

    private void awaitStatus(HubProjectRegistry reg, String name, ProjectStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ProjectStatus actual = null;
        while (System.currentTimeMillis() < deadline) {
            var entry = reg.entry(name);
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

    private void awaitReindexStatus(HubProjectRegistry reg, String name, ReindexStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ReindexStatus actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = reg.getReindexStatus(name);
            if (actual == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(actual).as("reindex status of '%s'", name).isEqualTo(expected);
    }

    /** Stub: the export writes one valid embedded record to the given output file. */
    private void stubExportWritesRecord(String id, String content) throws Exception {
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId(id);
                cc.setContent(content);
            });
            EmbeddedChunkRecord rec = EmbeddedChunkRecord.from(c, v);
            Files.writeString(out, new ObjectMapper().writeValueAsString(rec) + "\n");
            return null;
        }).when(exportService).exportForProject(any(), any(), any());
    }

    /** Pre-populates the shared index with one chunk stamped for the given project. */
    private void indexOldChunk(String project, String id) {
        TextSegment stamped = TextSegment.from(
                "old content of " + id,
                Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, project, PxChunk.PXCHUNK_ID, id)));
        luceneStore.addAll(List.of(Embedding.from(new float[8])), List.of(stamped));
    }

    @Test
    void onImportedWalksStatusFromImportingToReady() throws Exception {
        HubProjectRegistry spied = spy(registry);
        orchestrator = new PipelineOrchestrator(spied, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicReference<ProjectDefinition> defAtExport = new AtomicReference<>();
        doAnswer(inv -> {
            defAtExport.set(inv.getArgument(0, ProjectDefinition.class));
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("new-1");
                cc.setContent("fresh content");
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            embedded.countDown();
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator.onImported("alpha", projectDir("alpha"));

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(spied, "alpha", ProjectStatus.READY);

        var inOrder = inOrder(spied);
        inOrder.verify(spied).registerProject(eq("alpha"), any());
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.CHUNKING, null);
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.EMBEDDING, null);
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.READY, null);

        assertThat(spied.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        // the export ran on the project's definition and the new chunk landed in the shared index
        assertThat(defAtExport.get().getName()).isEqualTo("alpha");
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "new-1"))).isTrue();
    }

    @Test
    void pipelineRunRefreshesInMemoryStatsCache() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // cache: 0 chunks (empty index)
        indexOldChunk("alpha", "old-1");   // the index now holds 1 chunk, but the cache still says 0

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubExportWritesRecord("new-1", "fresh content");

        orchestrator.enqueue("alpha");

        awaitStatus(registry, "alpha", ProjectStatus.READY);

        // The pipeline swapped the index (old chunk gone, new chunk in) — the Web UI's stats must
        // reflect the new index. The refresh happens in the run's finally block, so poll briefly.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && registry.projectInfos().get(0).chunkCount() != 1) {
            Thread.sleep(10);
        }
        assertThat(registry.projectInfos().get(0).chunkCount()).isEqualTo(1);
    }

    @Test
    void reImportDoesNotLeaveDuplicateDaoInProvider() throws Exception {
        PxChunkDaoProvider spiedProvider = spy(new PxChunkDaoProvider(List.of()));
        HubProjectRegistry reg = new HubProjectRegistry(cfg, hub, spiedProvider, luceneStore, embeddingModel, parser, scanner);
        orchestrator = new PipelineOrchestrator(reg, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        CountDownLatch firstExportStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEmbedded = new CountDownLatch(1);
        AtomicInteger exportCalls = new AtomicInteger();
        doAnswer(inv -> {
            int n = exportCalls.incrementAndGet();
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("chunk-" + n);
                cc.setContent("content " + n);
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            if (n == 1) {
                firstExportStarted.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);   // hold the worker busy so the re-import supersedes
            } else {
                secondEmbedded.countDown();
            }
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator.onImported("alpha", projectDir("alpha"));
        assertThat(firstExportStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // re-import of the same name while the first run is in flight: stale DAO must be dropped,
        // and the in-flight reindex must be superseded (not skipped on the stale index)
        orchestrator.onImported("alpha", projectDir("alpha-reimport"));
        releaseFirst.countDown();   // let the first run finish so the superseding second run can start
        assertThat(secondEmbedded.await(10, TimeUnit.SECONDS)).isTrue();

        verify(spiedProvider, times(2)).register(any(PxChunkDao.class));
        verify(spiedProvider, times(1)).unregisterByProject("alpha");

        ArgumentCaptor<PxChunkDao> registered = ArgumentCaptor.forClass(PxChunkDao.class);
        verify(spiedProvider, times(2)).register(registered.capture());

        // the provider holds exactly one DAO for 'alpha' — the latest registration, no stale duplicate
        PxChunkDao latest = registered.getAllValues().get(1);
        assertThat(spiedProvider.get("alpha")).isPresent();
        assertThat(spiedProvider.get("alpha").orElseThrow()).isSameAs(latest);
        assertThat(reg.entry("alpha").orElseThrow().getStoreRef())
                .isSameAs(latest.getStoreReference());
    }

    @Test
    void chunkFailureMarksProjectFailedAndSkipsEmbedding() throws Exception {
        doThrow(new RuntimeException("chunking exploded")).when(chunkProcess).executeForProject(any());
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        orchestrator.onImported("alpha", projectDir("alpha"));

        awaitStatus(registry, "alpha", ProjectStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getLastError()).contains("chunking exploded");
        verifyNoInteractions(exportService);
    }

    @Test
    void singleWorkerExecutesPipelinesInFifoOrder() throws Exception {
        registry.registerProject("a", projectDir("a"));
        registry.registerProject("b", projectDir("b"));
        ProjectDefinition defA = registry.definitionOf("a");
        ProjectDefinition defB = registry.definitionOf("b");

        CountDownLatch embedded = new CountDownLatch(2);
        doAnswer(inv -> {
            ProjectDefinition def = inv.getArgument(0, ProjectDefinition.class);
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("chunk-" + def.getName());
                cc.setContent("content " + def.getName());
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            embedded.countDown();
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("a");
        orchestrator.enqueue("b");

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(registry, "a", ProjectStatus.READY);
        awaitStatus(registry, "b", ProjectStatus.READY);

        var inOrder = inOrder(chunkProcess);
        inOrder.verify(chunkProcess).executeForProject(defA);
        inOrder.verify(chunkProcess).executeForProject(defB);
    }

    @Test
    void selfHealMarksIndexedProjectReadyWithoutRunningPipeline() throws Exception {
        registry.registerProject("foo", projectDir("foo"));

        // index already contains a chunk stamped for 'foo' (e.g. from a previous run)
        indexOldChunk("foo", "old-1");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.selfHeal();   // synchronous

        assertThat(registry.entry("foo").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void onImportedSkipsPipelineWhenIndexAlreadyHasProjectChunks() throws Exception {
        indexOldChunk("foo", "old-1");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.onImported("foo", projectDir("foo"));

        assertThat(registry.entry("foo").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void selfHealEnqueuesFullPipelineWhenIndexHasNoChunksForProject() throws Exception {
        registry.registerProject("bar", projectDir("bar"));   // index is empty for 'bar'

        CountDownLatch embedded = new CountDownLatch(1);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("chunk-bar");
                cc.setContent("content bar");
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            embedded.countDown();
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.selfHeal();   // enqueues the pipeline asynchronously

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(registry, "bar", ProjectStatus.READY);
        verify(chunkProcess).executeForProject(registry.definitionOf("bar"));
    }

    @Test
    void onFailedMarksKnownProjectAsFailed() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        orchestrator.onFailed("alpha", "tar extraction exploded");   // synchronous

        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getLastError()).isEqualTo("tar extraction exploded");
        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void onFailedForUnregisteredProjectIsIgnored() {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        orchestrator.onFailed("ghost", "tar extraction exploded");   // must not throw

        assertThat(registry.entry("ghost")).isEmpty();
    }

    // ------------------------------------------------------------------ Phase 06: race guard + idempotent enqueue

    @Test
    void midRunDeregistrationTriggersScopedWipeOfJustWrittenChunks() throws Exception {
        registry.registerProject("foo", projectDir("foo"));

        // the pipeline "writes" a chunk for 'foo' (via the import after the export),
        // then the marker is removed mid-run
        CountDownLatch embedded = new CountDownLatch(1);
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("chunk-written");
                cc.setContent("chunk written by the pipeline");
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            registry.unregisterProject("foo");   // e.g. live marker removed by the poller mid-run
            embedded.countDown();
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("foo");

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();   // export done, import follows

        awaitWipe("foo");   // the race guard must wipe what was just written (entry is gone)

        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();
    }

    @Test
    void enqueueForDeregisteredProjectIsSafeNoOp() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        assertThatCode(() -> orchestrator.enqueue("ghost")).doesNotThrowAnyException();   // entry gone before the run

        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void enqueueIsIdempotentWhilePipelineInFlight() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        registry.registerProject("a", projectDir("a"));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);   // hold the single worker busy
            return null;
        }).when(chunkProcess).executeForProject(any());
        stubExportWritesRecord("chunk-a", "content a");

        orchestrator.enqueue("a");
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        orchestrator.enqueue("a");   // same name while in flight — must be deduplicated, not queued twice

        release.countDown();
        awaitStatus(registry, "a", ProjectStatus.READY);

        verify(chunkProcess, times(1)).executeForProject(any());
    }

    private void awaitWipe(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name))) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name)))
                .as("scoped wipe of '%s' did not happen within the deadline", name)
                .isFalse();
    }

    // ------------------------------------------------------------------ Reindex progress tracking

    @Test
    void standardPipelineSetsReindexStatusDuringExecution() throws Exception {
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        CountDownLatch chunkingDone = new CountDownLatch(1);
        doAnswer(inv -> {
            // During chunking, reindex status should be CHUNKING (progress: 0 until the phase ends)
            assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.CHUNKING);
            assertThat(registry.getReindexProgress("alpha")).isEqualTo(0);
            chunkingDone.countDown();
            return null;
        }).when(chunkProcess).executeForProject(any());

        CountDownLatch embeddingDone = new CountDownLatch(1);
        doAnswer(inv -> {
            // During embedding, reindex status should be EMBEDDING (progress: 10 until the callback reports)
            assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.EMBEDDING);
            assertThat(registry.getReindexProgress("alpha")).isEqualTo(10);
            // write enough records that the swap (IMPORTING) is observable from the test thread
            Path out = inv.getArgument(1);
            StringBuilder sb = new StringBuilder();
            ObjectMapper mapper = new ObjectMapper();
            for (int i = 0; i < 200; i++) {
                final int idx = i;
                float[] v = new float[8];
                Arrays.fill(v, 0.1f);
                PxChunk c = PxChunk.create(cc -> {
                    cc.setId("chunk-" + idx);
                    cc.setContent("content " + idx);
                });
                sb.append(mapper.writeValueAsString(EmbeddedChunkRecord.from(c, v))).append('\n');
            }
            Files.writeString(out, sb.toString());
            embeddingDone.countDown();
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator.enqueue("alpha");
        assertThat(chunkingDone.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(embeddingDone.await(10, TimeUnit.SECONDS)).isTrue();

        // the swap phase must be observed as IMPORTING (the batched import of 200 records takes long enough)
        boolean sawImporting = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ReindexStatus s = registry.getReindexStatus("alpha");
            if (s == ReindexStatus.IMPORTING) {
                sawImporting = true;
                break;
            }
            if (s == ReindexStatus.COMPLETE) {
                break;
            }
            Thread.sleep(1);
        }
        assertThat(sawImporting).as("reindex status IMPORTING was never observed during the swap").isTrue();

        // After pipeline completes, status should be COMPLETE
        awaitStatus(registry, "alpha", ProjectStatus.READY);
        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.COMPLETE);
        assertThat(registry.getReindexProgress("alpha")).isEqualTo(100);
    }

    @Test
    void enqueueDocPipeRunsDocPhaseOnlyWithoutCodePipeline() throws Exception {
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        AtomicReference<ReindexStatus> statusAtFinish = new AtomicReference<>();
        orchestrator.setPipelineListeners(List.of(new PipelineListener() {
            @Override
            public void onPipelineStart(PipelineOrchestrator o, String n, PipelineRun r) {
            }

            @Override
            public void onPipelineFinished(PipelineOrchestrator o, String n, PipelineRun r) {
                statusAtFinish.set(registry.getReindexStatus(n));
            }
        }));

        orchestrator.enqueueDocPipe("alpha");

        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);

        // the doc phase was marked during the run, and the code pipeline never ran
        assertThat(statusAtFinish.get()).isEqualTo(ReindexStatus.DOCPIPE);
        verify(chunkProcess, never()).executeForProject(any());
        verify(exportService, never()).exportForProject(any(), any(), any());
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void standardPipelineReportsMonotonicGranularProgress() throws Exception {
        HubProjectRegistry spied = spy(registry);
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(spied, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        // The export stub fires the progress callback like the real service does: (done, total) per record
        doAnswer(inv -> {
            BiConsumer<Integer, Integer> progress = inv.getArgument(2);
            Path out = inv.getArgument(1);
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                final int idx = i;
                float[] v = new float[8];
                Arrays.fill(v, 0.1f);
                PxChunk c = PxChunk.create(cc -> {
                    cc.setId("chunk-" + idx);
                    cc.setContent("content " + idx);
                });
                sb.append(mapper.writeValueAsString(EmbeddedChunkRecord.from(c, v))).append('\n');
                progress.accept(i + 1, 10);
            }
            Files.writeString(out, sb.toString());
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator.enqueue("alpha");
        awaitReindexStatus(spied, "alpha", ReindexStatus.COMPLETE);

        ArgumentCaptor<Integer> progressCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(spied, atLeastOnce()).setReindexProgress(eq("alpha"), progressCaptor.capture());
        List<Integer> values = progressCaptor.getAllValues();

        assertThat(values).isNotEmpty();
        assertThat(values).allSatisfy(v -> assertThat(v).isBetween(0, 100));
        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i)).as("progress at step %d", i).isGreaterThanOrEqualTo(values.get(i - 1));
        }
        // the embedding phase must have produced values inside its reserved 10-80 band
        assertThat(values).anySatisfy(v -> assertThat(v).isBetween(11, 79));
        assertThat(values.get(values.size() - 1)).isEqualTo(100);
    }

    @Test
    void failedPipelineSetsReindexStatusToFailed() throws Exception {
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        doThrow(new RuntimeException("chunking failed"))
                .when(chunkProcess).executeForProject(any());

        orchestrator.enqueue("alpha");
        awaitStatus(registry, "alpha", ProjectStatus.FAILED);

        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.FAILED);
    }

    // ------------------------------------------------------------------ Keep-old-index semantics

    @Test
    void reindexKeepsOldIndexAvailableDuringEmbedding() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        indexOldChunk("alpha", "old-1");
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "alpha"))).isTrue();

        CountDownLatch embedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            embedStarted.countDown();
            release.await(10, TimeUnit.SECONDS);   // hold the worker in the embedding phase
            Path out = inv.getArgument(1);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("new-1");
                cc.setContent("new content");
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");

        assertThat(embedStarted.await(10, TimeUnit.SECONDS)).isTrue();
        // during the embedding: the old index is still available and the project stays searchable
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "alpha"))).isTrue();
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);

        release.countDown();
        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
    }

    @Test
    void embeddingFailureWithOldIndexKeepsProjectReady() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        indexOldChunk("alpha", "old-1");

        AtomicReference<Path> embeddedFile = new AtomicReference<>();
        doAnswer(inv -> {
            embeddedFile.set(inv.getArgument(1));
            throw new RuntimeException("embed exploded");
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");

        awaitReindexStatus(registry, "alpha", ReindexStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);   // old index in use
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-1"))).isTrue();        // old chunks intact
        assertThat(Files.exists(embeddedFile.get())).isFalse();                                        // temp file cleaned up
    }

    @Test
    void embeddingFailureWithoutOldIndexMarksFailed() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // index is empty

        doThrow(new RuntimeException("embed exploded")).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");

        awaitStatus(registry, "alpha", ProjectStatus.FAILED);
        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.FAILED);
    }

    @Test
    void swapReplacesOldChunksWithNew() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        indexOldChunk("alpha", "old-1");

        stubExportWritesRecord("new-1", "new content");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");

        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-1"))).isFalse();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "new-1"))).isTrue();
    }

    @Test
    void tempFileDeletedAfterRun() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        AtomicReference<Path> embeddedFile = new AtomicReference<>();
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            embeddedFile.set(out);
            float[] v = new float[8];
            Arrays.fill(v, 0.1f);
            PxChunk c = PxChunk.create(cc -> {
                cc.setId("new-1");
                cc.setContent("new content");
            });
            Files.writeString(out, new ObjectMapper().writeValueAsString(EmbeddedChunkRecord.from(c, v)) + "\n");
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");

        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);
        assertThat(embeddedFile.get()).isNotNull();
        assertThat(Files.exists(embeddedFile.get())).isFalse();   // temp file cleaned up
    }

    @Test
    void onImportedWithInFlightReindexSupersedesInsteadOfSkipping() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        // old index data: without the race guard, onImported would skip (READY) on this stale index
        indexOldChunk("alpha", "old-1");

        CountDownLatch embedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            embedStarted.countDown();
            release.await(10, TimeUnit.SECONDS);   // reindex in flight, blocked in the embedding phase
            return null;
        }).when(exportService).exportForProject(any(), any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");
        assertThat(embedStarted.await(10, TimeUnit.SECONDS)).isTrue();

        orchestrator.onImported("alpha", projectDir("alpha"));   // must supersede, not skip on the stale index

        // the re-registered entry is IMPORTING (a new run was enqueued) — not the READY skip
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.IMPORTING);

        release.countDown();
    }

    // ------------------------------------------------------------------ Phase 03: live update (runLiveUpdate two-phase flow)

    /** Stubs the (mocked) scanner to report the given three-way split and a live JSONL under the temp root. */
    private void stubScanner(String name, Set<String> toEmbed, Set<String> toDelete) {
        when(scanner.scan(any())).thenReturn(new LiveUpdateScanner.ScanResult(toEmbed, toDelete, 0));
        when(scanner.liveJsonlPath(any())).thenReturn(tempRoot.resolve("live").resolve(name + ".live-embedded.jsonl"));
    }

    /** Indexes one chunk stamped with project + id + file (for seeding the shared index). */
    private void indexFileChunk(String project, String id, String file) {
        TextSegment stamped = TextSegment.from(
                "old content of " + id,
                Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, project, PxChunk.PXCHUNK_ID, id, PxChunk.PXCHUNK_FILE, file)));
        luceneStore.addAll(List.of(Embedding.from(new float[8])), List.of(stamped));
    }

    private PxChunk chunk(String id, String file, String content) {
        return PxChunk.create(cc -> {
            cc.setId(id);
            cc.setFile(file);
            cc.setContent(content);
        });
    }

    private float[] vec() {
        float[] v = new float[8];
        Arrays.fill(v, 0.1f);
        return v;
    }

    /** Reads EmbeddedChunkRecord JSONL lines (test-side mirror of the orchestrator's reader). */
    private List<EmbeddedChunkRecord> readJsonlLines(Path path) throws Exception {
        List<EmbeddedChunkRecord> out = new ArrayList<>();
        try (var lines = Files.lines(path)) {
            lines.filter(l -> !l.isBlank()).forEach(l -> {
                try {
                    out.add(objMapper.readValue(l, EmbeddedChunkRecord.class));
                } catch (Exception e) {
                    throw new RuntimeException("Unparseable JSONL line in " + path, e);
                }
            });
        }
        return out;
    }

    /** Awaits the run's completion: {@code embeddingInFlight} is cleared in the pipeline's finally block. */
    private void awaitEmbeddingInFlight(HubProjectRegistry reg, String name, boolean expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        boolean actual = !expected;
        while (System.currentTimeMillis() < deadline) {
            var entry = reg.entry(name);
            if (entry.isPresent()) {
                actual = entry.get().isEmbeddingInFlight();
                if (actual == expected) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertThat(actual).as("embeddingInFlight of '%s'", name).isEqualTo(expected);
    }

    @Test
    void enqueueLiveUpdate_idempotent_whenReindexInFlight() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        // hold the single worker busy with a full reindex
        CountDownLatch reindexStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            reindexStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(chunkProcess).executeForProject(any());
        stubExportWritesRecord("chunk-a", "content a");

        orchestrator.enqueue("alpha");   // full reindex in flight
        assertThat(reindexStarted.await(10, TimeUnit.SECONDS)).isTrue();

        orchestrator.enqueueLiveUpdate("alpha");   // must be deduplicated (a run is already in flight)

        // the live update must NOT have started: no scan, embeddingInFlight stays false
        verify(scanner, never()).scan(any());
        assertThat(registry.entry("alpha").orElseThrow().isEmbeddingInFlight()).isFalse();

        release.countDown();
        awaitStatus(registry, "alpha", ProjectStatus.READY);
    }

    @Test
    void runLiveUpdate_embedsChangedAndPublishesJsonl() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("a.java"), "public class A {}");
        registry.registerProject("alpha", dir);
        indexFileChunk("alpha", "old-a", "a.java");   // the file is in the index (stale)

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of("a.java"), Set.of());
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(chunk("new-a", "a.java", "new content of a")));
        when(embedder.embed(any())).thenReturn(List.of(EmbeddedChunkRecord.from(chunk("new-a", "a.java", "new content of a"), vec())));

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);   // run completed

        // the .tmp was renamed to the final live JSONL, which carries the generatedAt watermark
        Path finalJsonl = tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl");
        Path tmp = tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl.tmp");
        assertThat(Files.exists(finalJsonl)).isTrue();
        assertThat(Files.exists(tmp)).isFalse();
        List<EmbeddedChunkRecord> published = readJsonlLines(finalJsonl);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).generatedAt()).isNotNull();

        // the new a.java chunk is in the index (the stale one was removed first)
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_FILE, "a.java"))).isTrue();
        assertThat(registry.entry("alpha").orElseThrow().isEmbeddingInFlight()).isFalse();
    }

    @Test
    void runLiveUpdate_setsEmbeddingRunningWhileExecuting() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("a.java"), "public class A {}");
        registry.registerProject("alpha", dir);
        indexFileChunk("alpha", "old-a", "a.java");   // the file is in the index (stale)

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of("a.java"), Set.of());
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(chunk("new-a", "a.java", "new content of a")));
        AtomicBoolean runningDuringEmbed = new AtomicBoolean(false);
        when(embedder.embed(any())).thenAnswer(inv -> {
            runningDuringEmbed.set(registry.entry("alpha").orElseThrow().isEmbeddingRunning());
            return List.of(EmbeddedChunkRecord.from(chunk("new-a", "a.java", "new content of a"), vec()));
        });

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);   // run completed

        assertThat(runningDuringEmbed).as("embeddingRunning while the update is executing").isTrue();
        assertThat(registry.entry("alpha").orElseThrow().isEmbeddingRunning()).isFalse();   // cleared after
    }

    @Test
    void runLiveUpdate_batchesEmbeddingCalls() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("a.java"), "public class A {}");
        registry.registerProject("alpha", dir);
        registry.definitionOf("alpha").setTibedBatchSize(2);   // force small batches

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of("a.java"), Set.of());

        // 5 chunks with batchSize 2 -> embed() must be called 3 times (2 + 2 + 1), never with > 2.
        // Regression: a first scan is a FULL update, so `chunks` can be thousands of entries. Sending
        // them in one embedAll() is a single giant request a local model times out on (langchain4j's
        // OpenAiEmbeddingModel defaults maxSegmentsPerBatch to 2048, so it would NOT split them).
        List<PxChunk> chunks = List.of(
                chunk("c1", "a.java", "content 1"),
                chunk("c2", "a.java", "content 2"),
                chunk("c3", "a.java", "content 3"),
                chunk("c4", "a.java", "content 4"),
                chunk("c5", "a.java", "content 5"));
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(chunks);
        when(embedder.embed(any())).thenAnswer(inv -> {
            List<PxChunk> batch = inv.getArgument(0);
            return batch.stream().map(c -> EmbeddedChunkRecord.from(c, vec())).toList();
        });

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);   // run completed

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PxChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(embedder, times(3)).embed(captor.capture());
        assertThat(captor.getAllValues().stream().map(List::size).toList())
                .as("every embedding request stays within tibedBatchSize")
                .containsExactly(2, 2, 1);
    }

    @Test
    void runLiveUpdate_deletesRemovedFiles() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        indexFileChunk("alpha", "old-1", "old.java");   // in the index, gone from disk

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of(), Set.of("old.java"));   // nothing to embed, one file to delete
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of());
        when(embedder.embed(any())).thenReturn(List.of());

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);

        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_FILE, "old.java"))).isFalse();   // deleted
    }

    @Test
    void runLiveUpdate_noChanges_noOp() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of(), Set.of());   // empty scan -> no-op

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);

        // no-op: no chunking, no embedding, no .tmp, no final JSONL
        verify(chunkProcess, never()).executeForFiles(any(), any());
        verify(embedder, never()).embed(any());
        assertThat(Files.exists(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl"))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl.tmp"))).isFalse();
    }

    @Test
    void runLiveUpdate_failure_discardsTmpKeepsIndex() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("a.java"), "public class A {}");
        registry.registerProject("alpha", dir);
        indexFileChunk("alpha", "old-a", "a.java");   // the old index data that must survive

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        stubScanner("alpha", Set.of("a.java"), Set.of());
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(chunk("new-a", "a.java", "new content of a")));
        when(embedder.embed(any())).thenThrow(new RuntimeException("embed exploded"));

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);

        // the failure (in Phase 1) discards the .tmp and keeps the old index intact
        assertThat(Files.exists(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl.tmp"))).isFalse();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_ID, "old-a"))).isTrue();   // old chunk intact
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);   // stays searchable
    }

    @Test
    void runLiveUpdate_firesNoListeners() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("a.java"), "public class A {}");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        AtomicInteger listenerCalls = new AtomicInteger();
        orchestrator.setPipelineListeners(List.of(new PipelineListener() {
            @Override
            public void onPipelineStart(PipelineOrchestrator o, String n, PipelineRun r) {
                listenerCalls.incrementAndGet();
            }

            @Override
            public void onPipelineFinished(PipelineOrchestrator o, String n, PipelineRun r) {
                listenerCalls.incrementAndGet();
            }
        }));
        stubScanner("alpha", Set.of("a.java"), Set.of());
        when(chunkProcess.executeForFiles(any(), any())).thenReturn(List.of(chunk("new-a", "a.java", "new content of a")));
        when(embedder.embed(any())).thenReturn(List.of(EmbeddedChunkRecord.from(chunk("new-a", "a.java", "new content of a"), vec())));

        orchestrator.enqueueLiveUpdate("alpha");
        awaitEmbeddingInFlight(registry, "alpha", false);

        assertThat(listenerCalls.get()).as("a live update must fire no PipelineListener").isZero();
    }

    // ------------------------------------------------------------------ Phase 05: single-source rule (live JSONL after full rewrites)

    @Test
    void publishLiveJsonl_live_writesStampedJsonl() throws Exception {
        hub.setLiveJsonlDir(tempRoot.resolve("live").toString());
        registry.registerProject("alpha", projectDir("alpha"), ProjectEntry.Kind.LIVE);
        when(scanner.liveJsonlPath(any())).thenReturn(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        orchestrator.publishLiveJsonl("alpha", List.of(EmbeddedChunkRecord.from(chunk("c1", "a.java", "content"), vec())), 42L);   // synchronous

        Path jsonl = tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl");
        assertThat(Files.exists(jsonl)).isTrue();
        List<EmbeddedChunkRecord> published = readJsonlLines(jsonl);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).generatedAt()).isEqualTo(42L);
    }

    @Test
    void publishLiveJsonl_unpairedSurrogate_recordSkippedRestWritten() throws Exception {
        hub.setLiveJsonlDir(tempRoot.resolve("live").toString());
        registry.registerProject("alpha", projectDir("alpha"), ProjectEntry.Kind.LIVE);
        Path jsonl = tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl");
        when(scanner.liveJsonlPath(any())).thenReturn(jsonl);
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        EmbeddedChunkRecord good1 = EmbeddedChunkRecord.from(chunk("c1", "a.java", "before"), vec());
        // lone high surrogate (half an emoji) — not encodable as UTF-8
        EmbeddedChunkRecord broken = EmbeddedChunkRecord.from(chunk("c2", "b.java", "broken \uD83C"), vec());
        EmbeddedChunkRecord good2 = EmbeddedChunkRecord.from(chunk("c3", "c.java", "after \uD83E\uDDF1"), vec());

        orchestrator.publishLiveJsonl("alpha", List.of(good1, broken, good2), 42L);

        List<EmbeddedChunkRecord> published = readJsonlLines(jsonl);
        assertThat(published).extracting(EmbeddedChunkRecord::id).containsExactly("c1", "c3");
        assertThat(Files.readString(jsonl, StandardCharsets.UTF_8)).contains("after \uD83E\uDDF1");
    }

    @Test
    void publishLiveJsonl_snapshot_noWrite() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // SNAPSHOT (default kind)
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        orchestrator.publishLiveJsonl("alpha", List.of(EmbeddedChunkRecord.from(chunk("c1", "a.java", "content"), vec())), 42L);

        assertThat(Files.exists(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl"))).isFalse();
        verify(scanner, never()).liveJsonlPath(any());   // SNAPSHOT never resolves a live JSONL path
    }

    @Test
    void publishLiveJsonl_unknownName_noWrite() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        assertThatCode(() -> orchestrator.publishLiveJsonl("ghost", List.of(EmbeddedChunkRecord.from(chunk("c1", "a.java", "content"), vec())), 42L))
                .doesNotThrowAnyException();
        verify(scanner, never()).liveJsonlPath(any());
    }

    @Test
    void publishLiveJsonl_writeFailureIsLoggedNotThrown() throws Exception {
        hub.setLiveJsonlDir(tempRoot.resolve("live").toString());
        registry.registerProject("alpha", projectDir("alpha"), ProjectEntry.Kind.LIVE);
        Path blocker = tempRoot.resolve("blocker");
        Files.writeString(blocker, "x");   // a regular file where the parent directory must be
        when(scanner.liveJsonlPath(any())).thenReturn(blocker.resolve("alpha.live-embedded.jsonl"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);

        assertThatCode(() -> orchestrator.publishLiveJsonl("alpha", List.of(EmbeddedChunkRecord.from(chunk("c1", "a.java", "content"), vec())), 42L))
                .doesNotThrowAnyException();   // the index is already updated — a JSONL failure must not fail the run
        assertThat(Files.exists(blocker.resolve("alpha.live-embedded.jsonl"))).isFalse();
    }

    @Test
    void runStandardPipeline_live_rewritesLiveJsonl() throws Exception {
        hub.setLiveJsonlDir(tempRoot.resolve("live").toString());
        registry.registerProject("alpha", projectDir("alpha"), ProjectEntry.Kind.LIVE);
        when(scanner.liveJsonlPath(any())).thenReturn(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl"));
        stubExportWritesRecord("new-1", "new content");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        long before = System.currentTimeMillis();
        orchestrator.enqueue("alpha");
        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);

        Path jsonl = tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl");
        assertThat(Files.exists(jsonl)).isTrue();
        List<EmbeddedChunkRecord> published = readJsonlLines(jsonl);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).generatedAt()).isNotNull();
        assertThat(published.get(0).generatedAt()).isBetween(before, System.currentTimeMillis());   // ≈ run start
    }

    @Test
    void runStandardPipeline_snapshot_noLiveJsonl() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // SNAPSHOT (default kind)
        stubExportWritesRecord("new-1", "new content");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, embedder, scanner);
        orchestrator.enqueue("alpha");
        awaitReindexStatus(registry, "alpha", ReindexStatus.COMPLETE);

        assertThat(Files.exists(tempRoot.resolve("live").resolve("alpha.live-embedded.jsonl"))).isFalse();
        verify(scanner, never()).liveJsonlPath(any());   // a SNAPSHOT reindex never touches the live JSONL
    }
}
