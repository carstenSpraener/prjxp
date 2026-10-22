package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/globals")
@RequiredArgsConstructor
public class GlobalsController {

    private static final String MASKED_SECRET = "********";
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile(".*\\$\\{[A-Za-z0-9_.-]+(?::[^}]*)?}.*");

    private final GlobalsService globalsService;

    @GetMapping("/chat-models")
    public List<PrjXPChatModelReference> getChatModels() {
        return globalsService.getChatModels().stream().map(this::maskChatModelSecrets).toList();
    }

    @PostMapping("/chat-models")
    public ResponseEntity<PrjXPChatModelReference> createChatModel(@RequestBody PrjXPChatModelRequest request) {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        copyRequestToModel(request, model);
        PrjXPChatModelReference created = globalsService.createChatModel(model);
        return ResponseEntity.ok(maskChatModelSecrets(created));
    }

    @PutMapping("/chat-models/{name}")
    public ResponseEntity<PrjXPChatModelReference> updateChatModel(
            @PathVariable String name,
            @RequestBody PrjXPChatModelRequest request) {
        GlobalsService.PrjXPChatModelRequest serviceRequest = new GlobalsService.PrjXPChatModelRequest();
        copyToServiceRequest(request, serviceRequest);
        PrjXPChatModelReference updated = globalsService.updateChatModel(name, serviceRequest);
        return ResponseEntity.ok(maskChatModelSecrets(updated));
    }

    @DeleteMapping("/chat-models/{name}")
    public ResponseEntity<Void> deleteChatModel(@PathVariable String name) {
        globalsService.deleteChatModel(name);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/server-types")
    public List<String> getServerTypes() {
        return globalsService.getServerTypes();
    }

    @GetMapping("/embedding-model")
    public PrjXPEmbeddingModelReference getEmbeddingModel() {
        return maskEmbeddingModelSecrets(globalsService.getEmbeddingModel());
    }

    @PutMapping("/embedding-model")
    public ResponseEntity<PrjXPEmbeddingModelReference> updateEmbeddingModel(@RequestBody PrjXPEmbeddingModelRequest request) {
        GlobalsService.PrjXPEmbeddingModelRequest svcRequest = new GlobalsService.PrjXPEmbeddingModelRequest();
        if (request.getServerType() != null) svcRequest.setServerType(request.getServerType());
        if (request.getProviderUrl() != null) svcRequest.setProviderUrl(request.getProviderUrl());
        if (request.getModelName() != null) svcRequest.setModelName(request.getModelName());
        if (request.getApiKey() != null) svcRequest.setApiKey(request.getApiKey());
        if (request.getTimeoutSecs() != null) svcRequest.setTimeoutSecs(request.getTimeoutSecs());
        return ResponseEntity.ok(maskEmbeddingModelSecrets(globalsService.updateEmbeddingModel(svcRequest)));
    }

    @PostMapping("/chat-models/test")
    public ResponseEntity<ModelTestResult> testChatModel(@RequestBody PrjXPChatModelRequest request) {
        return ResponseEntity.ok(globalsService.testChatModel(request));
    }

    @PostMapping("/embedding-model/test")
    public ResponseEntity<ModelTestResult> testEmbeddingModel(@RequestBody PrjXPEmbeddingModelRequest request) {
        return ResponseEntity.ok(globalsService.testEmbeddingModel(request));
    }

    @GetMapping("/chat-models/models")
    public List<String> listChatModels(@RequestParam(required = false) String serverType,
                                       @RequestParam(required = false) String providerUrl,
                                       @RequestParam(required = false) String apiKey,
                                       @RequestParam(required = false) String stereoType) {
        return globalsService.listChatModels(serverType, providerUrl, apiKey, stereoType);
    }

    @GetMapping("/embedding-model/models")
    public List<String> listEmbeddingModels(@RequestParam(required = false) String serverType,
                                            @RequestParam(required = false) String providerUrl,
                                            @RequestParam(required = false) String apiKey) {
        return globalsService.listEmbeddingModels(serverType, providerUrl, apiKey);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    private void copyRequestToModel(PrjXPChatModelRequest request, PrjXPChatModelReference model) {
        if (request.getStereoType() != null) model.setStereoType(request.getStereoType());
        if (request.getServerType() != null) model.setServerType(request.getServerType());
        if (request.getModelName() != null) model.setModelName(request.getModelName());
        if (request.getApiKey() != null) model.setApiKey(request.getApiKey());
        if (request.getProviderUrl() != null) model.setProviderUrl(request.getProviderUrl());
        if (request.getTimeoutSecs() != null) model.setTimeoutSecs(request.getTimeoutSecs());
        if (request.getTemperature() != null) model.setTemperature(request.getTemperature());
        if (request.getPrompt() != null) model.setPrompt(request.getPrompt());
        if (request.getArgs() != null && !request.getArgs().isEmpty()) model.setArgs(new HashMap<>(request.getArgs()));
    }

    private PrjXPChatModelReference maskChatModelSecrets(PrjXPChatModelReference source) {
        PrjXPChatModelReference masked = new PrjXPChatModelReference();
        masked.setStereoType(source.getStereoType());
        masked.setServerType(source.getServerType());
        masked.setModelName(source.getModelName());
        masked.setApiKey(maskSecretValue(source.getApiKey()));
        masked.setProviderUrl(source.getProviderUrl());
        masked.setTimeoutSecs(source.getTimeoutSecs());
        masked.setTemperature(source.getTemperature());
        masked.setPrompt(source.getPrompt());
        if (source.getArgs() != null) {
            masked.setArgs(maskArgumentValues(source.getArgs()));
        }
        return masked;
    }

    private PrjXPEmbeddingModelReference maskEmbeddingModelSecrets(PrjXPEmbeddingModelReference source) {
        PrjXPEmbeddingModelReference masked = new PrjXPEmbeddingModelReference();
        masked.setServerType(source.getServerType());
        masked.setProviderUrl(source.getProviderUrl());
        masked.setModelName(source.getModelName());
        masked.setApiKey(maskSecretValue(source.getApiKey()));
        masked.setTimeoutSecs(source.getTimeoutSecs());
        return masked;
    }

    private String maskSecretValue(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (ENV_PLACEHOLDER.matcher(value).matches()) {
            return value;
        }
        return MASKED_SECRET;
    }

    private Map<String, String> maskArgumentValues(Map<String, String> args) {
        Map<String, String> masked = new HashMap<>();
        args.forEach((key, value) -> masked.put(key, maskSecretValue(value)));
        return masked;
    }

    private void copyToServiceRequest(PrjXPChatModelRequest req, GlobalsService.PrjXPChatModelRequest svc) {
        if (req.getStereoType() != null) svc.setStereoType(req.getStereoType());
        if (req.getServerType() != null) svc.setServerType(req.getServerType());
        if (req.getModelName() != null) svc.setModelName(req.getModelName());
        if (req.getApiKey() != null) svc.setApiKey(req.getApiKey());
        if (req.getProviderUrl() != null) svc.setProviderUrl(req.getProviderUrl());
        if (req.getTimeoutSecs() != null) svc.setTimeoutSecs(req.getTimeoutSecs());
        if (req.getTemperature() != null) svc.setTemperature(req.getTemperature());
        if (req.getPrompt() != null) svc.setPrompt(req.getPrompt());
        if (req.getArgs() != null && !req.getArgs().isEmpty()) svc.setArgs(new HashMap<>(req.getArgs()));
    }

    /** Request DTO for creating/updating chat models. */
    public static class PrjXPChatModelRequest {
        private String stereoType;
        private String serverType;
        private String modelName;
        private String apiKey;
        private String providerUrl;
        private Integer timeoutSecs;
        private Double temperature;
        private String prompt;
        private java.util.Map<String, String> args = new HashMap<>();

        public String getStereoType() { return stereoType; }
        public void setStereoType(String stereoType) { this.stereoType = stereoType; }
        public String getServerType() { return serverType; }
        public void setServerType(String serverType) { this.serverType = serverType; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProviderUrl() { return providerUrl; }
        public void setProviderUrl(String providerUrl) { this.providerUrl = providerUrl; }
        public Integer getTimeoutSecs() { return timeoutSecs; }
        public void setTimeoutSecs(Integer timeoutSecs) { this.timeoutSecs = timeoutSecs; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public java.util.Map<String, String> getArgs() { return args; }
        public void setArgs(java.util.Map<String, String> args) { this.args = args; }
    }

    /** Request DTO for updating the single embedding model config. */
    public static class PrjXPEmbeddingModelRequest {
        private String serverType;
        private String providerUrl;
        private String modelName;
        private String apiKey;
        private Integer timeoutSecs;

        public String getServerType() { return serverType; }
        public void setServerType(String serverType) { this.serverType = serverType; }
        public String getProviderUrl() { return providerUrl; }
        public void setProviderUrl(String providerUrl) { this.providerUrl = providerUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public Integer getTimeoutSecs() { return timeoutSecs; }
        public void setTimeoutSecs(Integer timeoutSecs) { this.timeoutSecs = timeoutSecs; }
    }
}
