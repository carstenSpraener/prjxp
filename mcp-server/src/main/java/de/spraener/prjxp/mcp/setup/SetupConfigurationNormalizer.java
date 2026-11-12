package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import de.spraener.prjxp.common.config.*;
import de.spraener.prjxp.mcp.hub.HubProperties;
import org.springframework.boot.context.properties.bind.BindException;

import java.io.IOException;
import java.net.URI;
import java.util.*;

final class SetupConfigurationNormalizer {
    private final ConfigurationStore store;
    private final List<ChatModelSupplier> suppliers;

    SetupConfigurationNormalizer(ConfigurationStore store, List<ChatModelSupplier> suppliers) {
        this.store = store;
        this.suppliers = List.copyOf(suppliers);
    }

    PrjXPConfig configuration(Map<String, Object> root) { return bind(root, "prjxp", PrjXPConfig.class); }
    HubProperties hub(Map<String, Object> root) { return bind(root, "prjxp.hub", HubProperties.class); }

    private <T> T bind(Map<String, Object> root, String prefix, Class<T> type) {
        try { return store.bindForDisplay(root, prefix, type); }
        catch (BindException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof SetupException setup) throw setup;
                if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                        && cause.getMessage().startsWith("MISSING_SECRET:")) throw missing();
            }
            throw SetupData.invalid();
        }
    }

    Map<String, Object> embedding(Map<String, Object> root) {
        return SetupData.map(resolved(embeddingDraft(root)));
    }

    Map<String, Object> embeddingDraft(Map<String, Object> root) {
        PrjXPConfig.EmbeddingConfig config = configuration(root).getEmbedding();
        if (config.getType() == null) throw SetupData.invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", config.getType().name());
        result.put("api-base-url", config.getApiBaseURL());
        result.put("api-key", config.getApiKey());
        result.put("model-name", config.getModelName());
        result.put("dimensions", config.getDimensions());
        result.put("timeout", config.getTimeout());
        result.put("max-segments-per-batch", config.getMaxSegmentsPerBatch());
        result.put("trust-all-certs", config.isTrustAllCerts());
        result.put("custom-headers", config.getCustomHeaders());
        result.put("custom-query-params", config.getCustomQueryParams());
        return result;
    }

    Map<String, Object> chat(Map<String, Object> root) {
        return SetupData.map(resolved(chatDraft(root)));
    }

    Map<String, Object> chatDraft(Map<String, Object> root) {
        List<PrjXPChatModelReference> defaults = configuration(root).getChatModels().stream()
                .filter(model -> "default".equals(model.getStereoType())).toList();
        if (defaults.size() > 1) throw new SetupException("CONFIG_AMBIGUOUS", "chat", 409, "Multiple default chat models");
        PrjXPChatModelReference config = defaults.isEmpty() ? new PrjXPChatModelReference() : defaults.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stereo-type", "default");
        result.put("server-type", config.getServerType());
        result.put("model-name", config.getModelName());
        result.put("api-key", config.getApiKey());
        result.put("provider-url", config.getProviderUrl());
        result.put("timeout-secs", config.getTimeoutSecs());
        result.put("temperature", config.getTemperature());
        result.put("prompt", config.getPrompt());
        result.put("args", config.getArgs());
        result.put("supports-tools", config.getSupportsTools());
        result.put("body-properties", config.getBodyProperties());
        return result;
    }

    Map<String, Object> mergeEmbedding(Map<String, Object> root, EmbeddingConfigRequest request) {
        if (request == null || (Boolean.TRUE.equals(request.resetDimensions()) && request.dimensions() != null)) throw invalid();
        if (request.type() != null && !Set.of("OLLAMA", "OPEN_AI").contains(request.type())) throw invalid();
        Map<String, Object> defaults = embeddingDraft(root);
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> raw = SetupData.section(prjxp, "embedding");
        merge(raw, defaults, "type", request.type());
        merge(raw, defaults, "api-base-url", request.apiBaseURL());
        merge(raw, defaults, "model-name", request.modelName());
        merge(raw, defaults, "timeout", request.timeout());
        merge(raw, defaults, "max-segments-per-batch", request.maxSegmentsPerBatch());
        merge(raw, defaults, "dimensions", request.dimensions());
        if (Boolean.TRUE.equals(request.resetDimensions())) SetupData.put(raw, "dimensions", null);
        SetupData.put(raw, "api-key", secret(request.apiKey(), raw.containsKey("api-key")
                || raw.containsKey("apiKey") ? (String) SetupData.get(raw, "api-key") : (String) defaults.get("api-key")));
        SetupData.put(prjxp, "embedding", raw);
        SetupData.put(root, "prjxp", prjxp);
        validateEmbedding(embedding(root));
        return root;
    }

    Map<String, Object> mergeChat(Map<String, Object> root, ChatModelRequest request) {
        return mergeChat(root, request, null, null);
    }

    Map<String, Object> mergeChat(Map<String, Object> root, ChatModelRequest request,
                                  String prompt, Map<String, String> args) {
        if (request == null || (request.stereoType() != null && !"default".equals(request.stereoType()))) throw invalid();
        Map<String, Object> defaults = chatDraft(root);
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Object existing = SetupData.get(prjxp, "chat-models");
        List<Object> models = existing == null ? new ArrayList<>() : new ArrayList<>((List<?>) existing);
        int index = -1;
        for (int i = 0; i < models.size(); i++) {
            if ("default".equals(SetupData.get(SetupData.map(models.get(i)), "stereo-type"))) index = i;
        }
        Map<String, Object> raw = index < 0 ? new LinkedHashMap<>() : SetupData.map(models.get(index));
        SetupData.put(raw, "stereo-type", "default");
        merge(raw, defaults, "server-type", request.serverType());
        merge(raw, defaults, "model-name", request.modelName());
        merge(raw, defaults, "provider-url", request.providerUrl());
        merge(raw, defaults, "timeout-secs", request.timeoutSecs());
        merge(raw, defaults, "temperature", request.temperature());
        SetupData.put(raw, "api-key", secret(request.apiKey(), raw.containsKey("api-key")
                || raw.containsKey("apiKey") ? (String) SetupData.get(raw, "api-key") : (String) defaults.get("api-key")));
        if (prompt != null) SetupData.put(raw, "prompt", prompt.isBlank() ? null : prompt);
        if (args != null && !args.isEmpty()) {
            Map<String, Object> merged = SetupData.map(defaults.get("args"));
            for (var entry : args.entrySet()) {
                text(entry.getKey());
                merged.put(entry.getKey(), secret(entry.getValue(), (String) merged.get(entry.getKey())));
            }
            SetupData.put(raw, "args", merged);
        }
        if (index < 0) models.add(raw); else models.set(index, raw);
        SetupData.put(prjxp, "chat-models", models);
        SetupData.put(root, "prjxp", prjxp);
        validateChat(chat(root));
        return root;
    }

    private void merge(Map<String, Object> raw, Map<String, Object> defaults, String key, Object requested) {
        Object existing = SetupData.get(raw, key);
        SetupData.put(raw, key, requested != null ? requested : existing != null ? existing : defaults.get(key));
    }

    String secret(String requested, String existing) {
        if (requested == null || "********".equals(requested)) {
            if ("********".equals(requested) && existing == null) throw missing();
            return existing;
        }
        if (requested.startsWith("${")) {
            if (!requested.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")) throw invalid();
            resolve(requested);
        }
        if (requested.isEmpty()) return requested;
        if (existing != null) {
            try {
                if (requested.equals(resolve(existing))) return existing;
            } catch (SetupException error) {
                if (!"MISSING_SECRET".equals(error.code())) throw error;
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Replacing an unavailable existing secret with an explicit value");
            }
        }
        return requested;
    }

    String resolve(String value) {
        try { return store.resolve(value); }
        catch (IOException error) { throw new SetupException("CONFIG_READ_FAILED", null, 500, "Cannot resolve configuration secrets"); }
        catch (IllegalArgumentException error) { throw missing(); }
    }

    void validateEmbedding(Map<String, Object> config) {
        if (!Set.of("OLLAMA", "OPEN_AI").contains(config.get("type"))) throw invalid();
        url(config.get("api-base-url"));
        text(config.get("model-name"));
        positive(config.get("timeout"));
        positive(config.get("max-segments-per-batch"));
        if (config.get("dimensions") != null) positive(config.get("dimensions"));
        if ("OPEN_AI".equals(config.get("type"))) text(config.get("api-key"));
    }

    void validateChat(Map<String, Object> config) {
        text(config.get("server-type"));
        text(config.get("model-name"));
        url(config.get("provider-url"));
        positive(config.get("timeout-secs"));
        double temperature = ((Number) config.get("temperature")).doubleValue();
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) throw invalid();
        PrjXPChatModelReference ref = new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
                .convertValue(config, PrjXPChatModelReference.class);
        if (suppliers.stream().noneMatch(supplier -> supplier.canProvide(ref))) throw invalid();
        if (!"ollama".equals(ref.getServerType())) text(config.get("api-key"));
    }

    OptimizationRequest optimization(Map<String, Object> root) {
        PrjXPConfig config = configuration(root);
        GraphRerankConfig graph = bind(root, "prjxp.graph.rerank", GraphRerankConfig.class);
        int threads = store.bindOrDefault(root, "prjxp.docpipe.maxthreads", Integer.class, 5);
        return new OptimizationRequest(config.getEmbeddingStoreLucene().getVectorDimension(),
                config.getEmbedding().getMaxSegmentsPerBatch(), threads, config.getReaderMaxOutputChars(),
                graph.getFloor(), graph.getBGraph(), graph.getCap(), graph.getWBase(), config.getEmbedding().getTimeout());
    }

    Map<String, Object> mergeOptimization(Map<String, Object> root, OptimizationRequest request) {
        if (request == null) throw invalid();
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        update(prjxp, "embedding-store-lucene", "vector-dimension", request.vectorDimension());
        update(prjxp, "embedding", "max-segments-per-batch", request.maxSegmentsPerBatch());
        update(prjxp, "embedding", "timeout", request.embeddingTimeout());
        update(prjxp, "docpipe", "maxthreads", request.docpipeMaxThreads());
        if (request.readerMaxOutputChars() != null) SetupData.put(prjxp, "reader-max-output-chars", request.readerMaxOutputChars());
        Map<String, Object> graph = SetupData.section(prjxp, "graph");
        update(graph, "rerank", "floor", request.graphRerankFloor());
        update(graph, "rerank", "b-graph", request.graphRerankBGraph());
        update(graph, "rerank", "cap", request.graphRerankCap());
        update(graph, "rerank", "w-base", request.graphRerankWBase());
        if (!graph.isEmpty()) SetupData.put(prjxp, "graph", graph);
        SetupData.put(root, "prjxp", prjxp);
        validateOptimization(optimization(root));
        return root;
    }

    private void update(Map<String, Object> parent, String section, String key, Object value) {
        if (value == null) return;
        Map<String, Object> map = SetupData.section(parent, section);
        SetupData.put(map, key, value);
        SetupData.put(parent, section, map);
    }

    void validateOptimization(OptimizationRequest value) {
        positive(value.vectorDimension());
        positive(value.maxSegmentsPerBatch());
        positive(value.docpipeMaxThreads());
        positive(value.embeddingTimeout());
        if (value.readerMaxOutputChars() < 0) throw invalid();
        if (!Double.isFinite(value.graphRerankFloor()) || value.graphRerankFloor() < 0 || value.graphRerankFloor() > 1) throw invalid();
        for (double number : List.of(value.graphRerankBGraph(), value.graphRerankWBase()))
            if (!Double.isFinite(number) || number < 0) throw invalid();
        if (!Double.isFinite(value.graphRerankCap()) || value.graphRerankCap() <= 0) throw invalid();
    }

    Map<String, Object> runtime(Map<String, Object> root) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embedding", embeddingDraft(root));
        result.put("chat-models", configuration(root).getChatModels());
        result.put("optimization", optimization(root));
        return result;
    }

    private Object resolved(Object value) {
        if (value instanceof String text) return resolve(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key.toString(), resolved(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::resolved).toList();
        return value;
    }

    static String mask(String value) {
        return value == null || value.isEmpty() || value.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}") ? value : "********";
    }
    static void text(Object value) { if (!(value instanceof String text) || text.isBlank()) throw invalid(); }
    static void positive(Object value) { if (!(value instanceof Number number) || number.longValue() <= 0) throw invalid(); }
    static void url(Object value) {
        text(value);
        try {
            URI uri = URI.create((String) value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    static SetupException invalid() { return new SetupException("INVALID_REQUEST", null, 400, "Invalid setup configuration"); }
    static SetupException missing() { return new SetupException("MISSING_SECRET", null, 400, "Required secret is unavailable"); }
}
