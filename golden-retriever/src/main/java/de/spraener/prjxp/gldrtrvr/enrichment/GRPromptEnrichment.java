package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class GRPromptEnrichment {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final List<GoldenRetriever> retrieverList;
    @Value("${prjxp.gldrtrvr.maxcontentlength:50000}")
    private int maxContentLength;
    @Value("${prjxp.gldrtrvr.vector-window:50}")
    private int vectorWindow;

    public String enrich(String projectName, String prompt) {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(maxContentLength);
        return enrich(projectName, prompt, List.of(),
                params,
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information aus dem Projekt '%s':
                                %s
                                
                                """, projectName, context
                        ),
                c -> c.length() > 0);
    }

    public String enrich(String projectName, String prompt, double distance, int maxResults, boolean skeletonsOnly) {
        SearchParams params = new SearchParams(maxResults, distance, skeletonsOnly);
        params.setMaxContentLength(maxContentLength);
        String enrichedContext = enrich(projectName, prompt, List.of(),
                params,
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information in '%s':
                                %s
                                
                                """, projectName, context
                        ),
                c -> c.length() > 0);
        String fallbackInfo = "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)\n"
                .formatted(params.getEffectiveMinScore(), params.getFallbackRounds());
        return fallbackInfo + enrichedContext;
    }

    public String enrich(String projectName, String prompt, List<PxChunk> prefetchedChunks,
                         Function<String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        SearchParams params = new SearchParams(8, 0.85);
        params.setMaxContentLength(maxContentLength);
        return enrich(projectName, prompt, prefetchedChunks,
                params,
                this::reIterate,
                promptFormatter,
                contextValidator);
    }

    public String enrich(String projectName, String prompt,
                         List<PxChunk> prefetchedChunks,
                         SearchParams searchParams,
                         Function<SearchParams, SearchParams> iterationHandler,
                         Function<String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        boolean invalidPrompt = true;
        String overallContext = "";
        do {
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
            PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
            List<ScoredChunk> similarChunks = findRelevantScored(chunkDao, prompt, searchParams.getMaxResult(), searchParams.getMinScore());
            List<ScoredChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks.stream().map(c -> new ScoredChunk(c, 1.0)).toList());
            relevantChunks.addAll(similarChunks);

            StringBuilder sb = new StringBuilder();
            for( var gr : retrieverList ) {
                sb.append(gr.buildPromptForFindings(projectName, relevantChunks, searchParams, contextValidator));
            }
            overallContext = sb.toString();
            if (contextValidator != null && contextValidator.length > 0) {
                invalidPrompt = false;
                for (var pv : contextValidator) {
                    invalidPrompt |= !pv.apply(overallContext);
                }
            } else {
                invalidPrompt = relevantChunks.size() == 0;
            }
            if (invalidPrompt) {
                searchParams = iterationHandler.apply(searchParams);
            }
            if (searchParams.isAbort()) {
                return "Es konnte kein valider Kontext erstellt werden! " +
                        "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)."
                                .formatted(searchParams.getEffectiveMinScore(), searchParams.getFallbackRounds());
            }
        } while (invalidPrompt);

        return promptFormatter.apply(overallContext.toString());
    }

    private List<ScoredChunk> findRelevantScored(PxChunkDao chunkDao, String prompt, int maxResults, double minScore) {
        int retrievalWindow = Math.max(maxResults, vectorWindow);
        try {
            return chunkDao.searchVector(prompt, Map.of(), retrievalWindow).stream()
                    .filter(sc -> sc.score() >= minScore)
                    .toList();
        } catch (UnsupportedOperationException ignore) {
            return chunkDao.findRelevant(prompt, maxResults, minScore).stream()
                    .map(c -> new ScoredChunk(c, 1.0))
                    .toList();
        }
    }

    public SearchParams reIterate(SearchParams searchParams) {
        double minScoreBefore = searchParams.getMinScore();
        if (searchParams.getMaxResult() < 16) {
            searchParams.setMaxResult(searchParams.getMaxResult() + 2);
        } else {
            // Snap to the canonical 0.05 grid: next attempt is the highest grid point
            // strictly below the current threshold (e.g. 0.93 -> 0.90, 0.85 -> 0.80).
            // The epsilon keeps on-grid values strictly descending (avoids an infinite loop);
            // recomputing from the current value avoids accumulated float drift.
            searchParams.setMinScore(
                    Math.floor((searchParams.getMinScore() - 1e-9) / 0.05) * 0.05);
        }
        if (searchParams.getMinScore() < minScoreBefore) {
            searchParams.setFallbackRounds(searchParams.getFallbackRounds() + 1);
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
        }
        if (searchParams.getMinScore() < 0.5) {
            searchParams.setAbort(true);
        }
        return searchParams;
    }
}
