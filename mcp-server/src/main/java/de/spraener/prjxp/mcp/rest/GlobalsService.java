package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.chat.ServerTypes;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
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
    private final Path applicationYamlPath;

    public GlobalsService(PrjXPConfig prjXPConfig,
                          KIChatModelProvider kiChatModelProvider) {
        this.prjXPConfig = prjXPConfig;
        this.kiChatModelProvider = kiChatModelProvider;

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
        if (request.getArgs() != null && !request.getArgs().isEmpty()) updated.setArgs(new HashMap<>(request.getArgs()));

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

    private String resolveUpdatedApiKey(String requestedApiKey, String existingApiKey) {
        if (requestedApiKey == null || "********".equals(requestedApiKey)) {
            return existingApiKey;
        }
        return requestedApiKey;
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
