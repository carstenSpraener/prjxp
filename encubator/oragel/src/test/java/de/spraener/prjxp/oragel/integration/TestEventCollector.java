package de.spraener.prjxp.oragel.integration;

import de.spraener.prjxp.oragel.events.EnrichedPromptEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("test")
public class TestEventCollector {
    private final List<EnrichedPromptEvent> events = new ArrayList<>();

    @EventListener
    public void collect(EnrichedPromptEvent e) {
        events.add(e);
    }

    public List<EnrichedPromptEvent> getEvents() {
        return events;
    }
}
