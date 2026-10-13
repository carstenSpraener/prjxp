package de.spraener.prjxp.mcpproxy;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based SSE (Server-Sent Events) parser.
 * Handles single and multi-line data fields, event/id metadata, comments, and CRLF line endings.
 * Emits complete SseEvent objects once an event is fully assembled (blank line or end of stream).
 */
public class SseParser {

    private String currentEvent = null;
    private String currentId = null;
    private final StringBuilder dataBuilder = new StringBuilder();
    private boolean hasData = false;

    /** Parse a single SSE line and return any completed events. */
    public List<SseEvent> parseLine(String line) {
        // Strip trailing CR/LF
        String trimmed = line.replace("\r", "").replace("\n", "");

        // Comment lines (e.g. ": ping") — ignore entirely
        if (trimmed.startsWith(":")) {
            return List.of();
        }

        // Blank line — event boundary, emit if we have data
        if (trimmed.isEmpty()) {
            return flush();
        }

        // Parse field: value format
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx < 0) {
            // Malformed line, ignore
            return List.of();
        }

        String field = trimmed.substring(0, colonIdx).trim();
        // SSE spec: value is everything after "field: " (space after colon is optional but common)
        String value = colonIdx + 1 < trimmed.length() ? trimmed.substring(colonIdx + 1).trim() : "";

        switch (field) {
            case "data":
                if (hasData) {
                    dataBuilder.append('\n');
                }
                dataBuilder.append(value);
                hasData = true;
                break;
            case "event":
                currentEvent = value.isEmpty() ? null : value;
                break;
            case "id":
                currentId = value.isEmpty() ? null : value;
                break;
            default:
                // Ignore unknown fields (e.g. "retry:")
                break;
        }

        return List.of();
    }

    /** Flush any remaining data as a final event (end of stream). */
    public List<SseEvent> flush() {
        if (!hasData) {
            return List.of();
        }
        SseEvent event = new SseEvent(currentEvent, currentId, dataBuilder.toString());
        // Reset state for next event
        currentEvent = null;
        currentId = null;
        dataBuilder.setLength(0);
        hasData = false;
        return List.of(event);
    }

    /** Convenience: parse a full SSE message string (multiple lines) and return all events. */
    public List<SseEvent> parse(String sseMessage) {
        String[] lines = sseMessage.split("\n", -1); // keep trailing empty string
        List<SseEvent> allEvents = new ArrayList<>();
        for (String line : lines) {
            List<SseEvent> events = parseLine(line);
            allEvents.addAll(events);
        }
        // Flush any remaining data (end of stream)
        allEvents.addAll(flush());
        return allEvents;
    }
}
