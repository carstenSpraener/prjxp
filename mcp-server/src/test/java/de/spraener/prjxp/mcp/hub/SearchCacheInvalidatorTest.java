package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.mcp.cache.SearchResultCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 03: the invalidator drops a project's cached search results after a successful
 * pipeline run (the index changed) and does nothing on start (the old index is still in use).
 */
@ExtendWith(MockitoExtension.class)
class SearchCacheInvalidatorTest {

    @Mock
    SearchResultCache searchCache;

    @Mock
    PipelineOrchestrator orchestrator;

    @Test
    void onPipelineFinishedInvalidatesTheProject() {
        SearchCacheInvalidator invalidator = new SearchCacheInvalidator(searchCache);

        invalidator.onPipelineFinished(orchestrator, "myproj", PipelineRun.root("myproj"));

        verify(searchCache).invalidateProject("myproj");
    }

    @Test
    void onPipelineStartDoesNothing() {
        SearchCacheInvalidator invalidator = new SearchCacheInvalidator(searchCache);

        invalidator.onPipelineStart(orchestrator, "myproj", PipelineRun.root("myproj"));

        verifyNoInteractions(searchCache);
    }
}
