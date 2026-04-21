package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.code.java.JavaRetriever;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import de.spraener.prjxp.gldrtrvr.enrichment.SearchParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
        String prompt = promptEnrichment.enrich(
                question,
                prefetchedChunks,
                new SearchParams(8, 0.85),
                promptEnrichment::reIterate,
                this::formatContextForJavaDoc,
                contextValidator);
        return chat.chat(prompt);
    }

    public String askForJavaDoc(String template, String method, String className) {
        return ask(template.formatted(method, className));
    }

    private String formatContextForJavaDoc(String question, String context) {
        return String.format("""
                Du bist ein erfahrener Software-Architekt. Beantworte die Frage des Nutzers 
                ausschließlich basierend auf dem unten stehenden Kontext aus seinem Java-Projekt. 
                Wenn du die Antwort nicht im Kontext findest, sage das deutlich.
                
                Beantworte zunächst das WAS und dann mit dieser Erklärung das WARUM. Wenn Du das
                Warum nicht ermitteln kannst beschränke dich auf das was.
                
                KONTEXT AUS DEN PROJEKT-MODULEN:
                %s
                
                FRAGE: %s
                
                """, context, question);
    }
}
