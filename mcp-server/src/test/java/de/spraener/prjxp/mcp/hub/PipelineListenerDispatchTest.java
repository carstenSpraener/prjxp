package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.EmbeddingExportService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Tests the Phase-01 listener SPI: Spring-registered {@link PipelineListener}s are dispatched at
 * the start/finish of every run, gated by the per-chain trigger ledger (a listener fires at most
 * once per run chain — the loop breaker). Uses a REAL registry + real Lucene index (8-dim) and
 * mocked ChunkProcess/EmbeddingExportService, mirroring {@link PipelineOrchestratorTest}.
 */
class PipelineListenerDispatchTest {

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
        Path dir = Files.createDirectories(tempRoot.resolve("projects").resolve(name));
        Files.writeString(dir.resolve("prjxp.yaml"), "");   // empty config
        return dir;
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

    /**
     * A recording listener: counts start/finish invocations and captures the last run phase.
     * When {@code retriggerWork} is non-null it schedules a retrigger from onPipelineFinished.
     */
    private static class RecordingListener implements PipelineListener {
        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger finishCount = new AtomicInteger();
        final AtomicInteger lastPhase = new AtomicInteger();
        private final PipelineWork retriggerWork;

        RecordingListener(PipelineWork retriggerWork) {
            this.retriggerWork = retriggerWork;
        }

        @Override
        public void onPipelineStart(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
            startCount.incrementAndGet();
            lastPhase.set(run.phase());
        }

        @Override
        public void onPipelineFinished(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
            finishCount.incrementAndGet();
            lastPhase.set(run.phase());
            if (retriggerWork != null) {
                orchestrator.scheduleRetrigger(projectName, retriggerWork);
            }
        }
    }

    @Test
    void rootRunTriggersListenerOnce() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        RecordingListener listener = new RecordingListener(null);   // no retrigger
        orchestrator.setPipelineListeners(List.of(listener));

        orchestrator.enqueue("p1");
        awaitStatus(registry, "p1", ProjectStatus.READY);

        assertThat(listener.startCount.get()).isEqualTo(1);
        assertThat(listener.finishCount.get()).isEqualTo(1);
        assertThat(listener.lastPhase.get()).isEqualTo(1);
    }

    @Test
    void retriggerRunSkipsTriggeredListener() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        RecordingListener listener = new RecordingListener((n, d) -> { });   // no-op retrigger
        orchestrator.setPipelineListeners(List.of(listener));

        orchestrator.enqueue("p1");
        awaitStatus(registry, "p1", ProjectStatus.READY);

        // the listener fired exactly once (in the root run); the retrigger run inherited the ledger mark
        assertThat(listener.startCount.get()).isEqualTo(1);
        assertThat(listener.finishCount.get()).isEqualTo(1);
    }

    @Test
    void phaseStatusDuringRetriggerRun() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        CountDownLatch retriggerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener((n, d) -> {
            retriggerStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);   // hold the retrigger run in flight
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        orchestrator.setPipelineListeners(List.of(listener));

        orchestrator.enqueue("p1");
        assertThat(retriggerStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // while the retrigger (phase 2) is in flight, the display status must reflect the phase
        assertThat(registry.statusOf("p1")).isEqualTo("EMBEDDING_PHASE_2");

        release.countDown();
        awaitStatus(registry, "p1", ProjectStatus.READY);
    }

    @Test
    void failedRunDoesNotDispatchFinish() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        doThrow(new RuntimeException("boom")).when(chunkProcess).executeForProject(org.mockito.ArgumentMatchers.any());
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        RecordingListener listener = new RecordingListener(null);
        orchestrator.setPipelineListeners(List.of(listener));

        orchestrator.enqueue("p1");
        awaitStatus(registry, "p1", ProjectStatus.FAILED);

        assertThat(listener.startCount.get()).isEqualTo(1);
        assertThat(listener.finishCount.get()).isZero();   // no finish dispatch on a broken run
    }

    @Test
    void noListenersKeepsCurrentBehavior() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
        // no setPipelineListeners call — the default empty list must preserve current behavior

        orchestrator.enqueue("p1");
        awaitStatus(registry, "p1", ProjectStatus.READY);
    }

    @Test
    void scheduleRetriggerOutsideRunIsIgnored() throws Exception {
        registry.registerProject("p1", projectDir("p1"));
        orchestrator = new PipelineOrchestrator(registry, chunkProcess, exportService, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);

        // no run in flight — scheduleRetrigger must be a safe no-op (no exception)
        assertThatCode(() -> orchestrator.scheduleRetrigger("p1", (n, d) -> { })).doesNotThrowAnyException();

        // a subsequent enqueue still runs to READY
        orchestrator.enqueue("p1");
        awaitStatus(registry, "p1", ProjectStatus.READY);
    }
}
