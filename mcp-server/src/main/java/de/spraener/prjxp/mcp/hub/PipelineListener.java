package de.spraener.prjxp.mcp.hub;

/**
 * Extension point for pipeline runs. Spring-registered listeners are notified at the start and
 * finish of runs, gated by the per-chain trigger ledger: each callback (start and finish) is
 * invoked at most once per run chain. In a root run a listener therefore receives both start and
 * finish; in a retrigger run it receives neither (the ledger mark is inherited), which breaks
 * feedback loops. Listeners may schedule further runs (retriggers) via the orchestrator.
 */
public interface PipelineListener {

    /** Called when a run starts, before its work executes (at most once per chain). */
    void onPipelineStart(PipelineOrchestrator orchestrator, String projectName, PipelineRun run);

    /** Called when a run's work finished successfully (NOT on failure), before the terminal status is set (at most once per chain). */
    void onPipelineFinished(PipelineOrchestrator orchestrator, String projectName, PipelineRun run);
}
