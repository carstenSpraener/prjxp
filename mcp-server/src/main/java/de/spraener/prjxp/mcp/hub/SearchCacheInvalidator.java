package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.mcp.cache.SearchResultCache;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 03: keeps the {@link SearchResultCache} fresh without touching the orchestrator or the
 * registry. After every SUCCESSFUL pipeline run the project's index changed, so all cached
 * results of that project are dropped; the next query reloads from the new index. The cache TTL
 * remains as a safety net for projects whose runs never finish.
 *
 * <p>Spring registers this listener automatically via the orchestrator's
 * {@code setPipelineListeners} — no constructor changes in {@code PipelineOrchestrator} or
 * {@code HubProjectRegistry}.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SearchCacheInvalidator implements PipelineListener {

    private final SearchResultCache searchCache;

    @Override
    public void onPipelineStart(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
        // no-op: the old index is still in use during the run, so the cache is still valid
    }

    @Override
    public void onPipelineFinished(PipelineOrchestrator orchestrator, String projectName, PipelineRun run) {
        searchCache.invalidateProject(projectName);
    }
}
