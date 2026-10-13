package de.spraener.prjxp.mcpproxy;

import lombok.Value;

/**
 * Represents a single SSE (Server-Sent Events) event.
 */
@Value
public class SseEvent {

    /** Event type, e.g. "message", "ping". Null means default event. */
    String event;

    /** Event ID, if provided by the server. */
    String id;

    /** The concatenated data payload (multi-line data fields are joined with newlines). */
    String data;

    @Override
    public String toString() {
        return "SseEvent{event='" + event + "', id='" + id + "', data='" + data + "'}";
    }
}
