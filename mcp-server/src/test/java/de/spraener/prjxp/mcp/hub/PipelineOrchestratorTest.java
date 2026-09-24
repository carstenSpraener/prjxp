package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * mocked ChunkProcess/EmbeddingService. Async completion is awaited via latches / status polling.
 */
class PipelineOrchestratorTest {

    @TempDir
    Path tempRoot;

    private HubProperties hub;
    private PrjXPConfig cfg;
    private PxChunkDaoProvider provider;
    private LuceneEmbeddingStore luceneStore;
    private EmbeddingModel embeddingModel;   // mocked — never invoked (chunk/embed are mocked)
    private ProjectConfigFileParser parser;
    private HubProjectRegistry registry;

    private ChunkProcess chunkProcess;       // mocked
    private EmbeddingService embeddingService;  // mocked

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
        embeddingService = mock(EmbeddingService.class);
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

    @Test
    void onImportedWalksStatusFromImportingToReady() throws Exception {
        HubProjectRegistry spied = spy(registry);
        orchestrator = new PipelineOrchestrator(spied, chunkProcess, embeddingService, luceneStore);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicReference<ProjectDefinition> defAtEmbed = new AtomicReference<>();
        doAnswer(inv -> {
            defAtEmbed.set(inv.getArgument(0, ProjectDefinition.class));
            embedded.countDown();
            return null;
        }).when(embeddingService).executeForProject(any(), any());

        orchestrator.onImported("alpha", projectDir("alpha"));

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(spied, "alpha", ProjectStatus.READY);

        var inOrder = inOrder(spied);
        inOrder.verify(spied).registerProject(eq("alpha"), any());
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.CHUNKING, null);
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.EMBEDDING, null);
        inOrder.verify(spied).setStatus("alpha", ProjectStatus.READY, null);

        assertThat(spied.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        // import = source of truth: the definition was stamped for a scoped reset before embedding ran
        assertThat(defAtEmbed.get().isTibedResetStore()).isTrue();
    }

    @Test
    void reImportDoesNotLeaveDuplicateDaoInProvider() throws Exception {
        PxChunkDaoProvider spiedProvider = spy(new PxChunkDaoProvider(List.of()));
        HubProjectRegistry reg = new HubProjectRegistry(cfg, hub, spiedProvider, luceneStore, embeddingModel, parser);
        orchestrator = new PipelineOrchestrator(reg, chunkProcess, embeddingService, luceneStore);

        CountDownLatch firstEmbedded = new CountDownLatch(1);
        CountDownLatch secondEmbedded = new CountDownLatch(1);
        AtomicInteger embedCalls = new AtomicInteger();
        doAnswer(inv -> {
            int n = embedCalls.incrementAndGet();
            if (n == 1) {
                firstEmbedded.countDown();
            } else {
                secondEmbedded.countDown();
            }
            return null;
        }).when(embeddingService).executeForProject(any(), any());

        orchestrator.onImported("alpha", projectDir("alpha"));
        assertThat(firstEmbedded.await(10, TimeUnit.SECONDS)).isTrue();

        // re-import of the same name: stale DAO must be dropped before the new one is registered
        orchestrator.onImported("alpha", projectDir("alpha-reimport"));
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
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);

        orchestrator.onImported("alpha", projectDir("alpha"));

        awaitStatus(registry, "alpha", ProjectStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getLastError()).contains("chunking exploded");
        verifyNoInteractions(embeddingService);
    }

    @Test
    void singleWorkerExecutesPipelinesInFifoOrder() throws Exception {
        registry.registerProject("a", projectDir("a"));
        registry.registerProject("b", projectDir("b"));
        ProjectDefinition defA = registry.definitionOf("a");
        ProjectDefinition defB = registry.definitionOf("b");

        CountDownLatch embedded = new CountDownLatch(2);
        doAnswer(inv -> {
            embedded.countDown();
            return null;
        }).when(embeddingService).executeForProject(any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);
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
        TextSegment stamped = TextSegment.from(
                "some indexed content",
                Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, "foo")));
        luceneStore.addAll(List.of(Embedding.from(new float[8])), List.of(stamped));

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);
        orchestrator.selfHeal();   // synchronous

        assertThat(registry.entry("foo").orElseThrow().getStatus()).isEqualTo(ProjectStatus.READY);
        verifyNoInteractions(chunkProcess, embeddingService);
    }

    @Test
    void selfHealEnqueuesFullPipelineWhenIndexHasNoChunksForProject() throws Exception {
        registry.registerProject("bar", projectDir("bar"));   // index is empty for 'bar'

        CountDownLatch embedded = new CountDownLatch(1);
        doAnswer(inv -> {
            embedded.countDown();
            return null;
        }).when(embeddingService).executeForProject(any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);
        orchestrator.selfHeal();   // enqueues the pipeline asynchronously

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();
        awaitStatus(registry, "bar", ProjectStatus.READY);
        verify(chunkProcess).executeForProject(registry.definitionOf("bar"));
    }

    @Test
    void onFailedMarksKnownProjectAsFailed() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);

        orchestrator.onFailed("alpha", "tar extraction exploded");   // synchronous

        assertThat(registry.entry("alpha").orElseThrow().getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(registry.entry("alpha").orElseThrow().getLastError()).isEqualTo("tar extraction exploded");
        verifyNoInteractions(chunkProcess, embeddingService);
    }

    @Test
    void onFailedForUnregisteredProjectIsIgnored() {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);

        orchestrator.onFailed("ghost", "tar extraction exploded");   // must not throw

        assertThat(registry.entry("ghost")).isEmpty();
    }

    // ------------------------------------------------------------------ Phase 06: race guard + idempotent enqueue

    @Test
    void midRunDeregistrationTriggersScopedWipeOfJustWrittenChunks() throws Exception {
        registry.registerProject("foo", projectDir("foo"));

        // the pipeline "writes" a chunk for 'foo' (real index), then the marker is removed mid-run
        CountDownLatch embedded = new CountDownLatch(1);
        doAnswer(inv -> {
            luceneStore.addAll(
                    List.of(Embedding.from(new float[8])),
                    List.of(TextSegment.from("chunk written by the pipeline",
                            Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, "foo")))));
            registry.unregisterProject("foo");   // e.g. live marker removed by the poller mid-run
            embedded.countDown();
            return null;
        }).when(embeddingService).executeForProject(any(), any());

        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);
        orchestrator.enqueue("foo");

        assertThat(embedded.await(10, TimeUnit.SECONDS)).isTrue();   // chunk is in the index now

        awaitWipe("foo");   // the race guard must wipe what was just written (entry is gone)

        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();
    }

    @Test
    void enqueueForDeregisteredProjectIsSafeNoOp() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);

        assertThatCode(() -> orchestrator.enqueue("ghost")).doesNotThrowAnyException();   // entry gone before the run

        verifyNoInteractions(chunkProcess, embeddingService);
    }

    @Test
    void enqueueIsIdempotentWhilePipelineInFlight() throws Exception {
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, embeddingService, luceneStore);
        registry.registerProject("a", projectDir("a"));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);   // hold the single worker busy
            return null;
        }).when(chunkProcess).executeForProject(any());

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
}
