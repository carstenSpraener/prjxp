package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduler behavior with a real {@link ProjectEntry}/{@link ProjectDefinition} and mocked
 * {@link HubProjectRegistry}/{@link LiveUpdateScanner}/{@link PipelineOrchestrator}.
 * {@link LiveUpdateScheduler#tick()} is invoked directly (no real timers); "now" is the wall clock,
 * so time-dependent assertions use before/after bounds.
 */
class LiveUpdateSchedulerTest {

    @TempDir
    Path tempRoot;

    private HubProperties props;
    private HubProjectRegistry registry;
    private LiveUpdateScanner scanner;
    private PipelineOrchestrator orchestrator;
    private LiveUpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new HubProperties();
        props.setLiveUpdateEnabled(true);
        props.setLiveUpdateBaseIntervalMs(300_000);    // 5 min
        props.setLiveUpdateMaxIntervalMs(3_600_000);   // 60 min

        registry = mock(HubProjectRegistry.class);
        scanner = mock(LiveUpdateScanner.class);
        orchestrator = mock(PipelineOrchestrator.class);
        scheduler = new LiveUpdateScheduler(props, registry, scanner, orchestrator);
    }

    /** A LIVE, READY entry with live updates enabled. */
    private ProjectEntry liveEntry(String name) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setRootDir(tempRoot.resolve(name).toString());
        ProjectEntry entry = new ProjectEntry(name, tempRoot.resolve(name), ProjectEntry.Kind.LIVE, def, null);
        entry.setStatus(ProjectStatus.READY, null);
        return entry;
    }

    /** Wires the mocked registry to return exactly this entry (reindex status IDLE by default). */
    private void register(ProjectEntry entry) {
        when(registry.availableProjects()).thenReturn(List.of(entry.getName()));
        when(registry.entry(entry.getName())).thenReturn(Optional.of(entry));
        when(registry.getReindexStatus(entry.getName())).thenReturn(ReindexStatus.IDLE);
    }

    private LiveUpdateScanner.ScanResult emptyScan() {
        return new LiveUpdateScanner.ScanResult(Set.of(), Set.of(), 0);
    }

    @Test
    void tick_disabled_noScan() {
        props.setLiveUpdateEnabled(false);
        ProjectEntry entry = liveEntry("p");
        register(entry);

        scheduler.tick();

        verify(scanner, never()).scan(any());
    }

    @Test
    void tick_notLive_noScan() {
        ProjectDefinition def = new ProjectDefinition();
        def.setName("p");
        ProjectEntry entry = new ProjectEntry("p", tempRoot.resolve("p"), ProjectEntry.Kind.SNAPSHOT, def, null);
        entry.setStatus(ProjectStatus.READY, null);
        register(entry);

        scheduler.tick();

        verify(scanner, never()).scan(any());
    }

    @Test
    void tick_perProjectOptOut_noScan() {
        ProjectEntry entry = liveEntry("p");
        entry.getDefinition().setLiveUpdate(false);
        register(entry);

        scheduler.tick();

        verify(scanner, never()).scan(any());
    }

    @Test
    void tick_notReady_noScan() {
        ProjectEntry entry = liveEntry("p");
        entry.setStatus(ProjectStatus.EMBEDDING, null);
        register(entry);

        scheduler.tick();

        verify(scanner, never()).scan(any());
    }

    @Test
    void tick_firstSight_schedulesBase() {
        ProjectEntry entry = liveEntry("p");   // nextScanAt = 0 (not yet scheduled)
        register(entry);

        long before = System.currentTimeMillis();
        scheduler.tick();
        long after = System.currentTimeMillis();

        assertThat(entry.getCurrentIntervalMs()).isEqualTo(props.getLiveUpdateBaseIntervalMs());
        assertThat(entry.getNextScanAt())
                .isBetween(before + props.getLiveUpdateBaseIntervalMs(), after + props.getLiveUpdateBaseIntervalMs());
        verify(scanner, never()).scan(any());   // first sight only schedules, does not scan
    }

    @Test
    void tick_inFlight_skipsWithoutDoubling() {
        ProjectEntry entry = liveEntry("p");
        entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        entry.setEmbeddingInFlight(true);
        register(entry);

        long intervalBefore = entry.getCurrentIntervalMs();
        scheduler.tick();

        verify(scanner, never()).scan(any());
        assertThat(entry.getCurrentIntervalMs()).isEqualTo(intervalBefore);   // unchanged — no doubling
    }

    @Test
    void tick_reindexActive_skips() {
        ProjectEntry entry = liveEntry("p");
        entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        register(entry);
        when(registry.getReindexStatus("p")).thenReturn(ReindexStatus.CHUNKING);   // actively running

        long intervalBefore = entry.getCurrentIntervalMs();
        scheduler.tick();

        verify(scanner, never()).scan(any());
        assertThat(entry.getCurrentIntervalMs()).isEqualTo(intervalBefore);   // unchanged — no doubling
    }

    @Test
    void tick_reindexComplete_doesNotBlock() {
        // A finished reindex (COMPLETE) is not "active" — it must not permanently disable live updates,
        // because the reindex status is never reset to IDLE after completion.
        ProjectEntry entry = liveEntry("p");
        entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        register(entry);
        when(registry.getReindexStatus("p")).thenReturn(ReindexStatus.COMPLETE);
        when(scanner.scan(entry)).thenReturn(emptyScan());

        scheduler.tick();

        verify(scanner).scan(entry);   // scan proceeds despite the terminal reindex status
    }

    @Test
    void tick_emptyScan_doublesInterval() {
        ProjectEntry entry = liveEntry("p");
        long base = props.getLiveUpdateBaseIntervalMs();
        entry.setCurrentIntervalMs(base);
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        register(entry);
        when(scanner.scan(entry)).thenReturn(emptyScan());

        long before = System.currentTimeMillis();
        scheduler.tick();
        long after = System.currentTimeMillis();

        assertThat(entry.getCurrentIntervalMs()).isEqualTo(base * 2);
        assertThat(entry.getNextScanAt()).isBetween(before + base * 2, after + base * 2);
        verify(orchestrator, never()).enqueueLiveUpdate(any());
    }

    @Test
    void tick_doubling_respectsCap() {
        ProjectEntry entry = liveEntry("p");
        long max = props.getLiveUpdateMaxIntervalMs();
        entry.setCurrentIntervalMs(max);   // already at the cap
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        register(entry);
        when(scanner.scan(entry)).thenReturn(emptyScan());

        scheduler.tick();

        assertThat(entry.getCurrentIntervalMs()).isEqualTo(max);   // stays at the cap
    }

    @Test
    void tick_changesFound_enqueuesAndResets() {
        ProjectEntry entry = liveEntry("p");
        long base = props.getLiveUpdateBaseIntervalMs();
        entry.setCurrentIntervalMs(base * 4);   // had been doubled a few times
        entry.setNextScanAt(System.currentTimeMillis() - 1_000);   // due
        register(entry);
        when(scanner.scan(entry)).thenReturn(new LiveUpdateScanner.ScanResult(Set.of("a.java"), Set.of(), 0));

        scheduler.tick();

        verify(orchestrator).enqueueLiveUpdate("p");
        assertThat(entry.getCurrentIntervalMs()).isEqualTo(base);   // reset to base
    }

    @Test
    void tick_rescanRequested_scansBeforeDue() {
        ProjectEntry entry = liveEntry("p");
        entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
        entry.setNextScanAt(System.currentTimeMillis() + 100_000);   // NOT due (in the future)
        entry.setRescanRequested(true);
        register(entry);
        when(scanner.scan(entry)).thenReturn(emptyScan());

        scheduler.tick();

        verify(scanner).scan(entry);                 // forced rescan despite not being due
        assertThat(entry.isRescanRequested()).isFalse();   // flag consumed
    }
}
