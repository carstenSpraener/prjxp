package de.spraener.prjxp.mcpproxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP client that forwards JSON-RPC messages to a streamable-http MCP server.
 * Handles session management (Mcp-Session-Id header), SSE response parsing,
 * and notification responses (202 Accepted).
 */
@Slf4j
public class StreamableHttpUpstream {

    private final HttpClient httpClient;
    private final URI serverUri;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Session state: captured from the first response's Mcp-Session-Id header
    private final AtomicReference<String> sessionId = new AtomicReference<>();

    public StreamableHttpUpstream(String serverUrl, int timeoutSeconds) {
        this.serverUri = URI.create(serverUrl);
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                .build();
    }

    /**
     * Send a JSON-RPC message to the upstream server and relay the response.
     * Returns a list of lines to write to stdout (may be empty for notifications).
     */
    public CompletableFuture<List<String>> send(String jsonRpcMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Validate that the input is valid JSON (catches malformed messages early)
                objectMapper.readTree(jsonRpcMessage);
            } catch (JsonProcessingException e) {
                log.warn("Invalid JSON on stdin: {}", e.getMessage());
                return List.of(jsonRpcError(-32700, "Invalid JSON: " + e.getMessage()));
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(serverUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcMessage));

            // Attach session ID if we have one
            String currentSession = sessionId.get();
            if (currentSession != null && !currentSession.isEmpty()) {
                requestBuilder.header("Mcp-Session-Id", currentSession);
            }

            try {
                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                // Capture session ID from response header (if present)
                response.headers().allValues("Mcp-Session-Id").stream()
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .ifPresent(id -> sessionId.set(id));

                int statusCode = response.statusCode();
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                log.debug("Upstream response: status={}, contentType={}", statusCode, contentType);

                // 202 Accepted — notification, no body to relay
                if (statusCode == 202) {
                    return List.of();
                }

                // Handle 404 — session lost, reset it
                if (statusCode == 404) {
                    sessionId.set(null);
                    log.info("Upstream returned 404, session reset.");
                }

                // Parse response based on content type
                if (contentType.contains("text/event-stream")) {
                    return parseSseResponse(response.body());
                } else {
                    // application/json or fallback: return as single line
                    String body = response.body().trim();
                    if (body.isEmpty()) {
                        return List.of();
                    }
                    return List.of(body);
                }

            } catch (IOException | InterruptedException e) {
                log.error("Upstream request failed: {}", e.getMessage());
                Thread.currentThread().interrupt(); // restore interrupt flag
                return List.of(jsonRpcError(-32000, "Upstream error: " + e.getMessage()));
            }
        });
    }

    /** Parse an SSE response body into a list of JSON lines. */
    private List<String> parseSseResponse(String sseBody) {
        SseParser parser = new SseParser();
        List<String> result = new java.util.ArrayList<>();

        String[] lines = sseBody.split("\n", -1);
        for (String line : lines) {
            List<SseEvent> events = parser.parseLine(line);
            for (SseEvent event : events) {
                if (event.getData() != null && !event.getData().isEmpty()) {
                    result.add(event.getData());
                }
            }
        }

        // Flush any remaining events (end of stream)
        List<SseEvent> finalEvents = parser.flush();
        for (SseEvent event : finalEvents) {
            if (event.getData() != null && !event.getData().isEmpty()) {
                result.add(event.getData());
            }
        }

        return result;
    }

    /** Send a DELETE request to terminate the upstream session. */
    public CompletableFuture<Void> closeSession() {
        String currentSession = sessionId.get();
        if (currentSession == null || currentSession.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(serverUri)
                .header("Mcp-Session-Id", currentSession)
                .DELETE()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((resp, err) -> {
                    if (err != null) {
                        log.warn("Session close failed: {}", err.getMessage());
                    }
                    sessionId.set(null);
                    return null;
                });
    }

    /** Format a JSON-RPC error response. */
    private String jsonRpcError(int code, String message) {
        try {
            return objectMapper.writeValueAsString(new JsonRpcError(code, message));
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}";
        }
    }

    /** Minimal JSON-RPC error object for serialization. */
    private static class JsonRpcError {
        public final String jsonrpc = "2.0";
        public final ErrorObject error;

        JsonRpcError(int code, String message) {
            this.error = new ErrorObject(code, message);
        }

        static class ErrorObject {
            public final int code;
            public final String message;

            ErrorObject(int code, String message) {
                this.code = code;
                this.message = message;
            }
        }
    }
}
