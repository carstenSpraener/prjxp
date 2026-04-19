package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.code.java.JavaRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class GRPromptEnrichment {
    private final PxChunkDao chunkDao;
    private final JavaRetriever javaRetriever;

    public String enrich(String prompt) {
        return enrich(prompt, List.of(),
                new SearchParams(10, 0.75),
                this::reIterate, (question, context) ->
                String.format("""
                        Du bist ein erfahrener Software-Architekt. Beantworte die Frage des Nutzers 
                        ausschließlich basierend auf dem unten stehenden Kontext aus seinem Java-Projekt. 
                        Wenn du die Antwort nicht im Kontext findest, sage das deutlich.
                        
                        KONTEXT AUS DEN PROJEKT-MODULEN:
                        %s
                        
                        FRAGE: %s
                        
                        """.formatted(context, question)
                ), c -> c.length() > 0);
    }

    public String enrich(String prompt, List<PxChunk> prefetchedChunks,
                         BiFunction<String, String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        return enrich(prompt, prefetchedChunks,
                new SearchParams(8, 0.85),
                this::reIterate,
                promptFormatter,
                contextValidator);
    }

    public String enrich(String prompt,
                         List<PxChunk> prefetchedChunks,
                         SearchParams searchParams,
                         Function<SearchParams, SearchParams> iterationHandler,
                         BiFunction<String, String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        boolean invalidPrompt = true;
        String overallContext = "";
        do {
            List<PxChunk> simialarChunks = chunkDao.findRelevant(prompt, searchParams.getMaxResult(), searchParams.getMinScore());
            List<PxChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks);
            relevantChunks.addAll(simialarChunks);

            StringBuilder sb = new StringBuilder();
            overallContext = javaRetriever.buildPromptForFindings(sb, relevantChunks, contextValidator).toString();
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

        return promptFormatter.apply(prompt, overallContext.toString());
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
