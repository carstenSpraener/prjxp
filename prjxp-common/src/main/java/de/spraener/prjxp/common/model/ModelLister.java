package de.spraener.prjxp.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class ModelLister {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Lists model names available at the given provider.
     * @throws IllegalArgumentException for unsupported server types (e.g. "custom") or missing Gemini key
     * @throws IllegalStateException on HTTP/IO failure or non-2xx response
     */
    public List<String> listModels(String serverType, String providerUrl, String apiKey, int timeoutSecs) {
        String type = serverType == null ? "" : serverType.toLowerCase();
        String url = EnvPlaceholderResolver.resolve(providerUrl);
        String key = EnvPlaceholderResolver.resolve(apiKey);
        return switch (type) {
            case "ollama" -> listOllama(url, timeoutSecs);
            case "openai", "openapi", "lm-studio" -> listOpenAiCompatible(url, key, timeoutSecs);
            case "gemini" -> listGemini(key, timeoutSecs);
            default -> throw new IllegalArgumentException(
                    "Model listing is not supported for server type '" + serverType + "'");
        };
    }

    private List<String> listOllama(String baseUrl, int timeoutSecs) {
        String url = baseUrl.replaceAll("/$", "") + "/api/tags";
        JsonNode root = getJson(url, null, timeoutSecs);
        return extractNames(root, "models", "name");
    }

    private List<String> listOpenAiCompatible(String baseUrl, String apiKey, int timeoutSecs) {
        String url = baseUrl.replaceAll("/$", "");
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        url = url + "/models";
        JsonNode root = getJson(url, apiKey, timeoutSecs);
        return extractNames(root, "data", "id");
    }

    private List<String> listGemini(String apiKey, int timeoutSecs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("An API key is required for Gemini model listing");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        JsonNode root = getJson(url, null, timeoutSecs);
        return extractNames(root, "models", "modelId");
    }

    private JsonNode getJson(String url, String bearerKey, int timeoutSecs) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(timeoutSecs));
            if (bearerKey != null && !bearerKey.isBlank()) {
                rb.header("Authorization", "Bearer " + bearerKey);
            }
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Provider returned HTTP " + resp.statusCode()
                        + " for " + url + ": " + truncate(resp.body()));
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing models from " + url, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to list models from " + url + ": " + e.getMessage(), e);
        }
    }

    private List<String> extractNames(JsonNode root, String arrayField, String nameField) {
        List<String> names = new ArrayList<>();
        if (root != null && root.has(arrayField) && root.get(arrayField).isArray()) {
            for (JsonNode node : root.get(arrayField)) {
                if (node.has(nameField) && node.get(nameField).isTextual()) {
                    names.add(node.get(nameField).asText());
                }
            }
        }
        return names;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
