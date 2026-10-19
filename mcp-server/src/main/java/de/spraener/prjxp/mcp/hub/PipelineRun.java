package de.spraener.prjxp.mcp.hub;

import java.util.HashSet;
import java.util.Set;

/**
 * Identity of one pipeline run within a chain. A root run (phase 1) is created by
 * enqueue/enqueueForced; retrigger runs (phase n+1) are scheduled from listener callbacks and
 * inherit the chain's trigger ledger. All runs of one chain share the SAME mutable ledger set,
 * so a listener is invoked at most once per chain (loop breaker).
 *
 * <p>All access happens on the single pipeline worker thread, so the ledger needs no
 * synchronization of its own.
 */
public final class PipelineRun {

    private final String name;
    private final int phase;
    /** Chain-shared, MUTABLE ledger of listener IDs that already received a callback in this chain. */
    private final Set<String> triggeredListeners;
    /** Work of a retrigger run (null for root runs). */
    private final PipelineWork work;
    /** Retrigger runs scheduled from this run's finish callbacks (pipeline thread only). */
    private int retriggersScheduled = 0;

    public PipelineRun(String name, int phase, Set<String> triggeredListeners, PipelineWork work) {
        this.name = name;
        this.phase = phase;
        this.triggeredListeners = triggeredListeners;
        this.work = work;
    }

    public static PipelineRun root(String name) {
        return new PipelineRun(name, 1, new HashSet<>(), null);
    }

    /** Next run in the chain — same ledger instance, next phase. */
    public PipelineRun nextPhase(PipelineWork work) {
        return new PipelineRun(name, phase + 1, triggeredListeners, work);
    }

    public String name() { return name; }
    public int phase() { return phase; }
    public Set<String> triggeredListeners() { return triggeredListeners; }
    public PipelineWork work() { return work; }

    /** Mark BEFORE invoking the listener: a retrigger scheduled mid-callback must inherit this mark. */
    public void markTriggered(String listenerId) { triggeredListeners.add(listenerId); }
    public boolean isTriggered(String listenerId) { return triggeredListeners.contains(listenerId); }

    public int retriggersScheduled() { return retriggersScheduled; }
    public void noteRetriggerScheduled() { retriggersScheduled++; }
}
