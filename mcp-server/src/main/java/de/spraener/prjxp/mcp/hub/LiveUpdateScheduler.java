package de.spraener.prjxp.mcp.hub;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives adaptive live-update polling on a fixed 30 s tick (read-only). Per LIVE project:
 * base 5 min, doubled after each empty scan (cap 60 min), reset to base when changes are
 * found. A scan is skipped (without doubling) while a run is in flight or a reindex is
 * actively executing. A {@code rescanRequested} flag (set by the orchestrator on completion)
 * forces an immediate rescan so files modified during a long run are caught within 30 s.
 * State is in-memory: a restart resumes at the base interval.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LiveUpdateScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateScheduler.class);

    private final HubProperties props;
    private final HubProjectRegistry registry;
    private final LiveUpdateScanner scanner;
    private final PipelineOrchestrator orchestrator;

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        if (!props.isLiveUpdateEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String name : registry.availableProjects()) {
            registry.entry(name).ifPresent(entry -> tickFor(entry, now));
        }
    }

    private void tickFor(ProjectEntry entry, long now) {
        if (entry.getKind() != ProjectEntry.Kind.LIVE) { return; }
        if (!entry.getDefinition().isLiveUpdate()) { return; }
        if (entry.getStatus() != ProjectStatus.READY) { return; }

        // First sight: schedule the first scan one base interval out (5 min after READY).
        if (entry.getNextScanAt() == 0) {
            entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
            entry.setNextScanAt(now + props.getLiveUpdateBaseIntervalMs());
            return;
        }

        // Skipped scan (run in flight or reindex actively executing) — do NOT double the interval.
        // Only *active* reindex states block: a finished (COMPLETE/FAILED) reindex must not
        // permanently disable live updates, because the status is not reset to IDLE after completion.
        if (entry.isEmbeddingInFlight()
                || registry.getReindexStatus(entry.getName()).isActive()) {
            return;
        }

        boolean due = entry.isRescanRequested() || now >= entry.getNextScanAt();
        if (!due) { return; }
        entry.setRescanRequested(false);

        LiveUpdateScanner.ScanResult result = scanner.scan(entry);
        if (result.isEmpty()) {
            long next = Math.min(entry.getCurrentIntervalMs() * 2, props.getLiveUpdateMaxIntervalMs());
            entry.setCurrentIntervalMs(next);
            entry.setNextScanAt(now + next);
            log.debug("Live scan of '{}' empty — next in {} ms", entry.getName(), next);
        } else {
            entry.setCurrentIntervalMs(props.getLiveUpdateBaseIntervalMs());
            entry.setNextScanAt(now + props.getLiveUpdateBaseIntervalMs());
            log.info("Live scan of '{}' found {} file(s) to embed, {} to delete — enqueuing",
                    entry.getName(), result.toEmbed().size(), result.toDelete().size());
            orchestrator.enqueueLiveUpdate(entry.getName());
        }
    }
}
