package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.gldrtrvr.code.java.JavaRetriever;
import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class GldRtrvrQuestioner {
    private final JavaRetriever javaRetriever;
    private final PxChunkDao chunkDao;
    private final KIChat chat;
    private final GRPromptEnrichment promptEnrichment;

    public String ask(String question, Function<String, Boolean>... contextValidator) {
        return ask(question, List.of(), contextValidator);
    }

    public String ask(String question, List<PxChunk> prefetchedChunks, Function<String, Boolean>... contextValidator) {
        boolean invalidPrompt = false;
        String overallContext = "";
        int maxResult = 8;
        double minScore = 0.85;
        do {
            List<PxChunk> simialarChunks = chunkDao.findRelevant(question, maxResult, minScore);
            List<PxChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks);
            relevantChunks.addAll(simialarChunks);

            StringBuilder sb = new StringBuilder();
            overallContext = javaRetriever.buildPromptForFindings(sb, relevantChunks, contextValidator).toString();
            if (contextValidator != null) {
                for (var pv : contextValidator) {
                    invalidPrompt |= !pv.apply(overallContext);
                }
            }
            if (invalidPrompt) {
                if (maxResult < 16) {
                    maxResult += 2;
                } else {
                    minScore -= 0.05;
                }
            }
            if (minScore < 0.5) {
                return "Es konnte kein valider Kontext erstellt werden!";
            }
        } while (invalidPrompt);

        String prompt = String.format("""
                Du bist ein erfahrener Software-Architekt. Beantworte die Frage des Nutzers 
                ausschließlich basierend auf dem unten stehenden Kontext aus seinem Java-Projekt. 
                Wenn du die Antwort nicht im Kontext findest, sage das deutlich.
                
                KONTEXT AUS DEN PROJEKT-MODULEN:
                %s
                
                FRAGE: %s
                
                ANTWORT:
                """, overallContext.toString(), question);
        return chat.chat(prompt);
    }

    public String askForJavaDoc(String template, String method, String className) {
        return ask(template.formatted(method, className));
    }
}
