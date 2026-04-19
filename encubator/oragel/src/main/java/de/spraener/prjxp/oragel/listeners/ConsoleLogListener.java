package de.spraener.prjxp.oragel.listeners;

import de.spraener.prjxp.oragel.events.EnrichedPromptEvent;
import lombok.extern.java.Log;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Log
public class ConsoleLogListener {
    @EventListener
    public void onPrompt(EnrichedPromptEvent event) {
        log.fine("DEBUG: Prompt mit " + event.getContent().length() + " Zeichen gefeuert.");
    }
}