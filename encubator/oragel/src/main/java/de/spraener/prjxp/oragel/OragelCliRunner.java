package de.spraener.prjxp.oragel;

import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import de.spraener.prjxp.oragel.events.EnrichedPromptEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log
public class OragelCliRunner {

    private final GRPromptEnrichment enrichment;
    private final PromptSource promptSource;
    private final ApplicationEventPublisher eventPublisher;

    public void startApp(String[] args) {
        log.info("--- 🔮 ORAGEL CLI gestartet ---");

        promptSource.stream()
                .filter(input -> !input.isBlank())
                .forEach(q -> {
                    if (PromptSource.isExitCommand(q)) {
                        return;
                    }
                    String p = enrichment.enrich(q);
                    eventPublisher.publishEvent(new EnrichedPromptEvent(p));
                })
        ;
        log.info("--- 🔮 ORAGEL beendet ---");
    }
}
