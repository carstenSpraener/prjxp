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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
        objMapper = new ObjectMapper();
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
        orchestrator = new PipelineOrchestrator(spied, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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
    void reImportDoesNotLeaveDuplicateDaoInProvider() throws Exception {
        PxChunkDaoProvider spiedProvider = spy(new PxChunkDaoProvider(List.of()));
        HubProjectRegistry reg = new HubProjectRegistry(cfg, hub, spiedProvider, luceneStore, embeddingModel, parser);
        orchestrator = new PipelineOrchestrator(reg, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.selfHeal();   // synchronous

        assertThat(registry.entry("foo").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void onImportedSkipsPipelineWhenIndexAlreadyHasProjectChunks() throws Exception {
        indexOldChunk("foo", "old-1");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.selfHeal();   // enqueues the pipeline asynchronously

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(registry, "bar", ProjectStatus.READY);
        verify(chunkProcess).executeForProject(registry.definitionOf("bar"));
    }

    @Test
    void onFailedMarksKnownProjectAsFailed() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        orchestrator.onFailed("alpha", "tar extraction exploded");   // synchronous

        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getLastError()).isEqualTo("tar extraction exploded");
        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void onFailedForUnregisteredProjectIsIgnored() {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.enqueue("foo");

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();   // export done, import follows

        awaitWipe("foo");   // the race guard must wipe what was just written (entry is gone)

        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();
    }

    @Test
    void enqueueForDeregisteredProjectIsSafeNoOp() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        assertThatCode(() -> orchestrator.enqueue("ghost")).doesNotThrowAnyException();   // entry gone before the run

        verifyNoInteractions(chunkProcess, exportService);
    }

    @Test
    void enqueueIsIdempotentWhilePipelineInFlight() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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
    void standardPipelineReportsMonotonicGranularProgress() throws Exception {
        HubProjectRegistry spied = spy(registry);
        Path dir = projectDir("alpha");
        registry.registerProject("alpha", dir);

        orchestrator = new PipelineOrchestrator(spied, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.enqueue("alpha");

        awaitStatus(registry, "alpha", ProjectStatus.FAILED);
        assertThat(registry.getReindexStatus("alpha")).isEqualTo(ReindexStatus.FAILED);
    }

    @Test
    void swapReplacesOldChunksWithNew() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        indexOldChunk("alpha", "old-1");

        stubExportWritesRecord("new-1", "new content");

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        orchestrator.enqueue("alpha");
        assertThat(embedStarted.await(10, TimeUnit.SECONDS)).isTrue();

        orchestrator.onImported("alpha", projectDir("alpha"));   // must supersede, not skip on the stale index

        // the re-registered entry is IMPORTING (a new run was enqueued) — not the READY skip
        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.IMPORTING);

        release.countDown();
    }
}
