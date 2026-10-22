package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.chat.ServerTypes;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.embedding.EmbeddingModelProvider;
import de.spraener.prjxp.common.model.ModelLister;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class GlobalsService {

    private static final Logger log = LoggerFactory.getLogger(GlobalsService.class);

    private final PrjXPConfig prjXPConfig;
    private final KIChatModelProvider kiChatModelProvider;
    private final EmbeddingModelProvider embeddingModelProvider;
    private final ModelLister modelLister;
    private final Path applicationYamlPath;

    public GlobalsService(PrjXPConfig prjXPConfig,
                          KIChatModelProvider kiChatModelProvider,
                          EmbeddingModelProvider embeddingModelProvider,
                          ModelLister modelLister) {
        this.prjXPConfig = prjXPConfig;
        this.kiChatModelProvider = kiChatModelProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.modelLister = modelLister;

        // Try multiple locations for application.yaml
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(System.getProperty("user.dir"), "application.yaml"));
        candidates.add(Path.of(System.getProperty("user.dir"), "mcp-server", "src", "main", "resources", "application.yaml"));

        // Check for PRJXP_CONFIG_FILE environment variable
        String envPath = System.getenv("PRJXP_CONFIG_FILE");
        if (envPath != null && !envPath.isBlank()) {
            candidates.add(0, Path.of(envPath));
        }

        this.applicationYamlPath = candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(candidates.get(0)); // Use first as fallback (will be created if needed)
        restoreRawModelValuesFromYaml();
    }

    /** Get all configured chat models. */
    public List<PrjXPChatModelReference> getChatModels() {
        return new ArrayList<>(prjXPConfig.getChatModels());
    }

    /** Create a new chat model. */
    public PrjXPChatModelReference createChatModel(PrjXPChatModelReference model) {
        if (prjXPConfig.getChatModels().stream()
                .anyMatch(m -> m.getStereoType() != null && m.getStereoType().equals(model.getStereoType()))) {
            throw new IllegalArgumentException("Chat model with stereoType '" + model.getStereoType() + "' already exists");
        }
        prjXPConfig.getChatModels().add(model);
        persistToYaml();
        kiChatModelProvider.clearCache();
        return model;
    }

    /** Update an existing chat model by stereoType name. */
    public PrjXPChatModelReference updateChatModel(String name, PrjXPChatModelRequest request) {
        List<PrjXPChatModelReference> models = prjXPConfig.getChatModels();
        Optional<PrjXPChatModelReference> existing = models.stream()
                .filter(m -> m.getStereoType() != null && m.getStereoType().equals(name))
                .findFirst();

        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Chat model '" + name + "' not found");
        }

        PrjXPChatModelReference updated = existing.get();
        if (request.getStereoType() != null) updated.setStereoType(request.getStereoType());
        if (request.getServerType() != null) updated.setServerType(request.getServerType());
        if (request.getModelName() != null) updated.setModelName(request.getModelName());
        if (request.getApiKey() != null) {
            updated.setApiKey(resolveUpdatedApiKey(request.getApiKey(), updated.getApiKey()));
        }
        if (request.getProviderUrl() != null) updated.setProviderUrl(request.getProviderUrl());
        if (request.getTimeoutSecs() != null) updated.setTimeoutSecs(request.getTimeoutSecs());
        if (request.getTemperature() != null) updated.setTemperature(request.getTemperature());
        if (request.getPrompt() != null) updated.setPrompt(request.getPrompt().isBlank() ? null : request.getPrompt());
        if (request.getArgs() != null && !request.getArgs().isEmpty()) {
            updated.setArgs(resolveUpdatedArgs(request.getArgs(), updated.getArgs()));
        }

        persistToYaml();
        kiChatModelProvider.clearCache();
        return updated;
    }

    /** Delete a chat model by stereoType name. */
    public void deleteChatModel(String name) {
        boolean removed = prjXPConfig.getChatModels().removeIf(m -> m.getStereoType() != null && m.getStereoType().equals(name));
        if (!removed) {
            throw new IllegalArgumentException("Chat model '" + name + "' not found");
        }
        persistToYaml();
        kiChatModelProvider.clearCache();
    }

    /** Get all supported server type strings. */
    public List<String> getServerTypes() {
        return Arrays.stream(ServerTypes.values())
                .map(ServerTypes::serverType)
                .toList();
    }

    /** Get the single embedding model configuration. Returns a default if not configured. */
    public PrjXPEmbeddingModelReference getEmbeddingModel() {
        if (prjXPConfig.getEmbeddingModel() == null) {
            return new PrjXPEmbeddingModelReference();
        }
        // Return a copy to prevent direct mutation of the config object
        PrjXPEmbeddingModelReference copy = new PrjXPEmbeddingModelReference();
        copy.setServerType(prjXPConfig.getEmbeddingModel().getServerType());
        copy.setProviderUrl(prjXPConfig.getEmbeddingModel().getProviderUrl());
        copy.setModelName(prjXPConfig.getEmbeddingModel().getModelName());
        copy.setApiKey(prjXPConfig.getEmbeddingModel().getApiKey());
        copy.setTimeoutSecs(prjXPConfig.getEmbeddingModel().getTimeoutSecs());
        return copy;
    }

    /** Update the single embedding model configuration and persist to YAML. */
    public PrjXPEmbeddingModelReference updateEmbeddingModel(PrjXPEmbeddingModelRequest request) {
        // Copy fields from the DTO to a new Reference object, using defaults for nulls
        PrjXPEmbeddingModelReference ref = new PrjXPEmbeddingModelReference();
        String existingApiKey = prjXPConfig.getEmbeddingModel() != null
                ? prjXPConfig.getEmbeddingModel().getApiKey() : null;
        ref.setServerType(request.getServerType() != null ? request.getServerType() : "ollama");
        ref.setProviderUrl(request.getProviderUrl());
        ref.setModelName(request.getModelName());
        ref.setApiKey(resolveUpdatedApiKey(request.getApiKey(), existingApiKey));
        ref.setTimeoutSecs(request.getTimeoutSecs() != null ? request.getTimeoutSecs() : 60);

        prjXPConfig.setEmbeddingModel(ref);
        persistToYaml();
        return getEmbeddingModel();
    }

    /** Test a (possibly unsaved) chat model configuration with a fresh, uncached model instance. */
    public ModelTestResult testChatModel(GlobalsController.PrjXPChatModelRequest request) {
        PrjXPChatModelReference ref = new PrjXPChatModelReference();
        if (request.getStereoType() != null) ref.setStereoType(request.getStereoType());
        if (request.getServerType() != null) ref.setServerType(request.getServerType());
        if (request.getModelName() != null) ref.setModelName(request.getModelName());
        ref.setApiKey(resolveMaskedChatApiKey(request.getApiKey(), request.getStereoType()));
        if (request.getProviderUrl() != null) ref.setProviderUrl(request.getProviderUrl());
        if (request.getTimeoutSecs() != null) ref.setTimeoutSecs(request.getTimeoutSecs());
        if (request.getTemperature() != null) ref.setTemperature(request.getTemperature());

        long start = System.currentTimeMillis();
        try {
            String response = kiChatModelProvider.createFreshKIChat(ref).chat("Say hello");
            return ModelTestResult.success("Chat test succeeded", System.currentTimeMillis() - start, response, null);
        } catch (Exception e) {
            return ModelTestResult.failure("Chat test failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /** Test a (possibly unsaved) embedding model configuration with a fresh model instance. */
    public ModelTestResult testEmbeddingModel(GlobalsController.PrjXPEmbeddingModelRequest request) {
        PrjXPEmbeddingModelReference ref = new PrjXPEmbeddingModelReference();
        ref.setServerType(request.getServerType() != null ? request.getServerType() : "ollama");
        ref.setProviderUrl(request.getProviderUrl());
        ref.setModelName(request.getModelName());
        ref.setApiKey(resolveMaskedEmbeddingApiKey(request.getApiKey()));
        ref.setTimeoutSecs(request.getTimeoutSecs() != null ? request.getTimeoutSecs() : 60);

        long start = System.currentTimeMillis();
        try {
            Response<Embedding> resp = embeddingModelProvider.createEmbeddingModel(ref).embed("test");
            return ModelTestResult.success("Embedding test succeeded", System.currentTimeMillis() - start, null, resp.content().vector().length);
        } catch (Exception e) {
            return ModelTestResult.failure("Embedding test failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /** List the model names available at the given chat-model provider. */
    public List<String> listChatModels(String serverType, String providerUrl, String apiKey, String stereoType) {
        return modelLister.listModels(serverType, providerUrl, resolveMaskedChatApiKey(apiKey, stereoType), 30);
    }

    /** List the model names available at the given embedding-model provider. */
    public List<String> listEmbeddingModels(String serverType, String providerUrl, String apiKey) {
        return modelLister.listModels(serverType, providerUrl, resolveMaskedEmbeddingApiKey(apiKey), 30);
    }

    private String resolveUpdatedApiKey(String requestedApiKey, String existingApiKey) {
        if (requestedApiKey == null || "********".equals(requestedApiKey)) {
            return existingApiKey;
        }
        return requestedApiKey;
    }

    /** Resolve the masked "********" placeholder back to the saved key of the chat model with the given stereoType. */
    private String resolveMaskedChatApiKey(String apiKey, String stereoType) {
        if (apiKey == null || !"********".equals(apiKey) || stereoType == null) return apiKey;
        return prjXPConfig.getChatModels().stream()
                .filter(m -> stereoType.equals(m.getStereoType()))
                .map(PrjXPChatModelReference::getApiKey)
                .findFirst().orElse(null);
    }

    /** Resolve the masked "********" placeholder back to the saved key of the single embedding model. */
    private String resolveMaskedEmbeddingApiKey(String apiKey) {
        if (apiKey == null || !"********".equals(apiKey)) return apiKey;
        return prjXPConfig.getEmbeddingModel() != null ? prjXPConfig.getEmbeddingModel().getApiKey() : null;
    }

    private Map<String, String> resolveUpdatedArgs(Map<String, String> requestedArgs, Map<String, String> existingArgs) {
        Map<String, String> resolved = new HashMap<>();
        requestedArgs.forEach((key, value) -> resolved.put(key,
                "********".equals(value) && existingArgs != null ? existingArgs.get(key) : value));
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private void restoreRawModelValuesFromYaml() {
        if (!Files.exists(applicationYamlPath)) {
            return;
        }
        try {
            Object loaded = new Yaml().load(Files.readString(applicationYamlPath));
            if (!(loaded instanceof Map<?, ?> root) || !(root.get("prjxp") instanceof Map<?, ?> prjxp)) {
                return;
            }
            Object rawChatModels = prjxp.get("chat-models");
            if (rawChatModels instanceof List<?> models) {
                for (int index = 0; index < Math.min(models.size(), prjXPConfig.getChatModels().size()); index++) {
                    if (models.get(index) instanceof Map<?, ?> rawModel) {
                        restoreRawChatModelValues(prjXPConfig.getChatModels().get(index), rawModel);
                    }
                }
            }
            if (prjxp.get("embedding-model") instanceof Map<?, ?> rawEmbedding
                    && prjXPConfig.getEmbeddingModel() != null) {
                restoreRawEmbeddingModelValues(prjXPConfig.getEmbeddingModel(), rawEmbedding);
            }
        } catch (Exception e) {
            log.warn("Failed to restore raw model values from {}: {}", applicationYamlPath, e.getMessage());
        }
    }

    private void restoreRawChatModelValues(PrjXPChatModelReference target, Map<?, ?> raw) {
        target.setStereoType(rawString(raw, "stereo-type", target.getStereoType()));
        target.setServerType(rawString(raw, "server-type", target.getServerType()));
        target.setModelName(rawString(raw, "model-name", target.getModelName()));
        target.setApiKey(rawString(raw, "api-key", target.getApiKey()));
        target.setProviderUrl(rawString(raw, "provider-url", target.getProviderUrl()));
        target.setPrompt(rawString(raw, "prompt", target.getPrompt()));
        if (raw.get("args") instanceof Map<?, ?> rawArgs) {
            Map<String, String> args = new HashMap<>();
            rawArgs.forEach((key, value) -> args.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
            target.setArgs(args);
        }
    }

    private void restoreRawEmbeddingModelValues(PrjXPEmbeddingModelReference target, Map<?, ?> raw) {
        target.setServerType(rawString(raw, "server-type", target.getServerType()));
        target.setProviderUrl(rawString(raw, "provider-url", target.getProviderUrl()));
        target.setModelName(rawString(raw, "model-name", target.getModelName()));
        target.setApiKey(rawString(raw, "api-key", target.getApiKey()));
    }

    private String rawString(Map<?, ?> raw, String key, String fallback) {
        return raw.containsKey(key) ? Objects.toString(raw.get(key), null) : fallback;
    }

    /** DTO for partial update of the embedding model. */
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

    /** Persist the current chat-models and embedding-model back to application.yaml. */
    private void persistToYaml() {
        try {
            Map<String, Object> fullConfig;
            if (Files.exists(applicationYamlPath)) {
                String content = Files.readString(applicationYamlPath);
                Object loaded = new Yaml().load(content);
                if (loaded instanceof Map<?, ?> map) {
                    fullConfig = new HashMap<>();
                    map.forEach((k, v) -> fullConfig.put(String.valueOf(k), v));
                } else {
                    fullConfig = new HashMap<>();
                }
            } else {
                fullConfig = new HashMap<>();
            }

            // Build the prjxp section
            Map<String, Object> prjxpSection = new LinkedHashMap<>();

            // Preserve existing non-chat-models/non-embedding-model keys
            if (fullConfig.containsKey("prjxp") && fullConfig.get("prjxp") instanceof Map<?, ?> existingPrjxp) {
                existingPrjxp.forEach((k, v) -> {
                    String key = String.valueOf(k);
                    if (!"chat-models".equals(key) && !"embedding-model".equals(key)) {
                        prjxpSection.put(key, v);
                    }
                });
            }

            // Build chat-models list as YAML-friendly maps
            List<Map<String, Object>> yamlModels = new ArrayList<>();
            for (PrjXPChatModelReference model : prjXPConfig.getChatModels()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                if (model.getStereoType() != null) entry.put("stereo-type", model.getStereoType());
                if (model.getServerType() != null) entry.put("server-type", model.getServerType());
                if (model.getModelName() != null) entry.put("model-name", model.getModelName());
                if (model.getApiKey() != null) entry.put("api-key", model.getApiKey());
                if (model.getProviderUrl() != null) entry.put("provider-url", model.getProviderUrl());
                if (model.getTimeoutSecs() != 60) entry.put("timeout-secs", model.getTimeoutSecs());
                if (model.getTemperature() != 0.0) entry.put("temperature", model.getTemperature());
                if (model.getPrompt() != null && !model.getPrompt().isBlank()) entry.put("prompt", model.getPrompt());
                if (model.getArgs() != null && !model.getArgs().isEmpty()) entry.put("args", model.getArgs());
                yamlModels.add(entry);
            }

            if (!yamlModels.isEmpty()) {
                prjxpSection.put("chat-models", yamlModels);
            }

            // Build embedding-model as YAML-friendly map (single object, not a list)
            if (prjXPConfig.getEmbeddingModel() != null) {
                Map<String, Object> embeddingEntry = new LinkedHashMap<>();
                PrjXPEmbeddingModelReference em = prjXPConfig.getEmbeddingModel();
                if (em.getServerType() != null) embeddingEntry.put("server-type", em.getServerType());
                if (em.getProviderUrl() != null) embeddingEntry.put("provider-url", em.getProviderUrl());
                if (em.getModelName() != null) embeddingEntry.put("model-name", em.getModelName());
                if (em.getApiKey() != null) embeddingEntry.put("api-key", em.getApiKey());
                if (em.getTimeoutSecs() != null && em.getTimeoutSecs() != 60) embeddingEntry.put("timeout-secs", em.getTimeoutSecs());
                prjxpSection.put("embedding-model", embeddingEntry);
            }

            fullConfig.put("prjxp", prjxpSection);

            // Write back using SnakeYaml
            String yamlContent = new Yaml().dump(fullConfig);
            Files.writeString(applicationYamlPath, yamlContent);
            log.info("Persisted config to {}", applicationYamlPath);

        } catch (Exception e) {
            log.error("Failed to persist config to {}: {}", applicationYamlPath, e.getMessage());
        }
    }

    /** DTO for partial update requests. */
    public static class PrjXPChatModelRequest {
        private String stereoType;
        private String serverType;
        private String modelName;
        private String apiKey;
        private String providerUrl;
        private Integer timeoutSecs;
        private Double temperature;
        private String prompt;
        private Map<String, String> args = new HashMap<>();

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
        public Map<String, String> getArgs() { return args; }
        public void setArgs(Map<String, String> args) { this.args = args; }
    }
}
