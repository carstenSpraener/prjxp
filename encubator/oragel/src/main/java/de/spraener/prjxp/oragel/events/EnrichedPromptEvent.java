package de.spraener.prjxp.oragel.events;

import lombok.Data;

@Data
public class EnrichedPromptEvent {
    private String content;

    public EnrichedPromptEvent(String content) {
        this.content = content;
    }

    public String toString() {
        return content;
    }
}