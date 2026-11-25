package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.mcp.setup.*;
import de.spraener.prjxp.mcp.setup.GlobalsConfigurationService.Versioned;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequestMapping("/api/globals")
@RequiredArgsConstructor
public class GlobalsController {
    private final GlobalsService globalsService;

    @GetMapping("/chat-models")
    public ResponseEntity<List<PrjXPChatModelReference>> getChatModels() { return response(globalsService.getChatModels()); }

    @PostMapping("/chat-models")
    public ResponseEntity<PrjXPChatModelReference> createChatModel(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "X-Setup-Test-Id", required = false) String testId,
            @RequestBody PrjXPChatModelRequest request) {
        return response(globalsService.createChatModel(version(version), request, testId));
    }

    @PutMapping("/chat-models/{name}")
    public ResponseEntity<PrjXPChatModelReference> updateChatModel(@PathVariable String name,
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "X-Setup-Test-Id", required = false) String testId,
            @RequestBody PrjXPChatModelRequest request) {
        return response(globalsService.updateChatModel(version(version), name, request, testId));
    }

    @DeleteMapping("/chat-models/{name}")
    public ResponseEntity<Void> deleteChatModel(@PathVariable String name,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(globalsService.deleteChatModel(version(version), name));
    }

    @GetMapping("/server-types")
    public List<String> getServerTypes() { return globalsService.getServerTypes(); }

    @GetMapping("/embedding-config")
    public ResponseEntity<EmbeddingConfigRequest> getEmbeddingConfig() { return response(globalsService.getEmbeddingConfig()); }

    @PutMapping("/embedding-config")
    public ResponseEntity<EmbeddingConfigRequest> updateEmbeddingConfig(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "X-Setup-Test-Id", required = false) String testId,
            @RequestBody EmbeddingConfigRequest request) {
        return response(globalsService.updateEmbeddingConfig(version(version), request, testId));
    }

    @PostMapping("/embedding-config/test")
    public ResponseEntity<SetupTestResult> testEmbeddingConfig(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestBody SetupTestRequest<EmbeddingConfigRequest> request) {
        String expected = version(version);
        if (!Objects.equals(expected, request.configVersion())) throw changed();
        SetupTestResult result = globalsService.testEmbeddingConfig(request);
        return response(new Versioned<>(result.configVersion(), result));
    }

    @GetMapping("/embedding-model")
    public ResponseEntity<PrjXPEmbeddingModelReference> getEmbeddingModel() { return response(globalsService.getEmbeddingModel()); }

    @PutMapping("/embedding-model")
    public ResponseEntity<PrjXPEmbeddingModelReference> updateEmbeddingModel(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "X-Setup-Test-Id", required = false) String testId,
            @RequestBody PrjXPEmbeddingModelRequest request) {
        return response(globalsService.updateEmbeddingModel(version(version), request, testId));
    }

    @PostMapping("/chat-models/test")
    public ResponseEntity<ModelTestResult> testChatModel(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestBody PrjXPChatModelRequest request) {
        return response(globalsService.testChatModel(version(version), request));
    }

    @PostMapping("/embedding-model/test")
    public ResponseEntity<ModelTestResult> testEmbeddingModel(
            @RequestHeader(value = "If-Match", required = false) String version,
            @RequestBody PrjXPEmbeddingModelRequest request) {
        return response(globalsService.testEmbeddingModel(version(version), request));
    }

    @GetMapping("/chat-models/models")
    public List<String> listChatModels(@RequestParam(required = false) String serverType,
            @RequestParam(required = false) String providerUrl, @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String stereoType) {
        return globalsService.listChatModels(serverType, providerUrl, apiKey, stereoType);
    }

    @GetMapping("/embedding-model/models")
    public List<String> listEmbeddingModels(@RequestParam(required = false) String serverType,
            @RequestParam(required = false) String providerUrl, @RequestParam(required = false) String apiKey) {
        return globalsService.listEmbeddingModels(serverType, providerUrl, apiKey);
    }

    @ExceptionHandler(SetupException.class)
    public ResponseEntity<SetupError> setupError(SetupException error) {
        return ResponseEntity.status(error.status()).body(new SetupError(error.code(), error.getMessage(), error.step()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<SetupError> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(new SetupError("INVALID_REQUEST", "Invalid Globals request", null));
    }

    private static String version(String header) {
        if (header == null || header.isBlank()) {
            throw new SetupException("PRECONDITION_REQUIRED", null, 428, "Load the current configuration and send If-Match");
        }
        String value = header.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) return value.substring(1, value.length() - 1);
        if (value.startsWith("W/") || value.contains(",") || "*".equals(value)) throw changed();
        return value;
    }

    private static SetupException changed() {
        return new SetupException("CONFIG_CHANGED", null, 409, "Configuration changed; reload before saving");
    }

    private static <T> ResponseEntity<T> response(Versioned<T> result) {
        return ResponseEntity.ok().eTag(result.configVersion()).body(result.value());
    }

    @Getter @Setter
    public static class PrjXPChatModelRequest {
        private String stereoType;
        private String serverType;
        private String modelName;
        private String apiKey;
        private String providerUrl;
        private Integer timeoutSecs;
        private Double temperature;
        private String prompt;
        private Map<String, String> args;
    }

    @Getter @Setter
    public static class PrjXPEmbeddingModelRequest {
        private String serverType;
        private String providerUrl;
        private String modelName;
        private String apiKey;
        private Integer timeoutSecs;
    }
}
