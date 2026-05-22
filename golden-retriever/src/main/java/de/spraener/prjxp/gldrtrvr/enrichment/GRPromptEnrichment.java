package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class GRPromptEnrichment {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final List<GoldenRetriever> retrieverList;

    public String enrich(String projectName, String prompt) {
        return enrich(projectName, prompt, List.of(),
                new SearchParams(20, 0.85),
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information aus dem Projekt '%s':
                                %s
                                
                                """, projectName, context
                        ),
                c -> c.length() > 0);
    }

    public String enrich(String projectName, String prompt, List<PxChunk> prefetchedChunks,
                         Function<String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        return enrich(projectName, prompt, prefetchedChunks,
                new SearchParams(8, 0.85),
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
            List<PxChunk> similarChunks = chunkDaoProvider.get(projectName).get().findRelevant(prompt, searchParams.getMaxResult(), searchParams.getMinScore());
            List<PxChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks);
            relevantChunks.addAll(similarChunks);

            StringBuilder sb = new StringBuilder();
            for( var gr : retrieverList ) {
                sb.append(gr.buildPromptForFindings(projectName, relevantChunks, contextValidator));
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
                return "Es konnte kein valider Kontext erstellt werden!";
            }
        } while (invalidPrompt);

        return promptFormatter.apply(overallContext.toString());
    }

    public SearchParams reIterate(SearchParams searchParams) {
        if (searchParams.getMaxResult() < 16) {
            searchParams.setMaxResult(searchParams.getMaxResult() + 2);
        } else {
            searchParams.setMinScore(searchParams.getMinScore() - 0.05);
        }
        if (searchParams.getMinScore() < 0.5) {
            searchParams.setAbort(true);
        }
        return searchParams;
    }
}
