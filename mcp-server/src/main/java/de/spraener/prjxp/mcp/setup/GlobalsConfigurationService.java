package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import de.spraener.prjxp.common.config.EnvFileWriter;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

@Service
public class GlobalsConfigurationService {
    private final ConfigurationStore store;
    private final SetupConfigurationNormalizer normalizer;
    private final SetupTestEvidenceService evidence;
    private final SetupIndexGuard index;

    public GlobalsConfigurationService(ConfigurationStore store, SetupTestEvidenceService evidence,
                                       List<ChatModelSupplier> suppliers) {
        this.store = store;
        this.evidence = evidence;
        normalizer = new SetupConfigurationNormalizer(store, suppliers);
        index = new SetupIndexGuard(store);
    }

    public record Versioned<T>(String configVersion, T value) {}

    public Versioned<EmbeddingConfigRequest> embedding() {
        return store.read(snapshot -> versioned(snapshot, this::embedding));
    }

    public Versioned<List<PrjXPChatModelReference>> chats() {
        return store.read(snapshot -> versioned(snapshot, this::chats));
    }

    public Versioned<EmbeddingConfigRequest> updateEmbedding(String version, EmbeddingConfigRequest request, String testId) {
        return store.update(version, snapshot -> {
            Map<String, Object> root = normalizer.mergeEmbedding(snapshot.root(), request);
            Map<String, Object> candidate = normalizer.embedding(root);
            Map<String, Object> proof = proof(snapshot.root(), "embedding", candidate, testId);
            index.require(root, candidate, proof);
            if (proof.get("dimensions") instanceof Number measured) {
                SetupConfigurationNormalizer.positive(measured);
                Map<String, Object> prjxp = SetupData.section(root, "prjxp");
                Map<String, Object> lucene = SetupData.section(prjxp, "embedding-store-lucene");
                SetupData.put(lucene, "vector-dimension", measured.intValue());
                SetupData.put(prjxp, "embedding-store-lucene", lucene);
                SetupData.put(root, "prjxp", prjxp);
            }
            putProof(root, "embedding", proof);
            Map<String, String> entries = new LinkedHashMap<>();
            Map<String, Object> prjxp = SetupData.section(root, "prjxp");
            Map<String, Object> raw = SetupData.section(prjxp, "embedding");
            migrateKey(raw, "PRJXP_EMBEDDING_API_KEY_", entries);
            SetupData.put(prjxp, "embedding", raw);
            SetupData.put(root, "prjxp", prjxp);
            return new ConfigurationStore.Update(root, entries);
        }, snapshot -> versioned(snapshot, this::embedding));
    }

    public Versioned<PrjXPChatModelReference> saveChat(String version, String name, ChatModelRequest request,
                                                     String prompt, Map<String, String> args, boolean create,
                                                     String testId) {
        if (request == null) throw SetupData.invalid();
        String target = request.stereoType() == null ? name : request.stereoType();
        SetupConfigurationNormalizer.text(target);
        return store.update(version, snapshot -> {
            Map<String, Object> root = snapshot.root();
            List<Object> models = rawChats(root);
            int position = find(models, name);
            if (create ? find(models, target) >= 0 : position < 0) throw chatConflict();
            if (!create && !target.equals(name) && find(models, target) >= 0) throw chatConflict();
            Map<String, Object> raw = create ? new LinkedHashMap<>() : SetupData.map(models.get(position));
            Map<String, Object> virtual = virtualChat(root, raw);
            normalizer.mergeChat(virtual, new ChatModelRequest("default", request.serverType(), request.modelName(),
                    request.apiKey(), request.providerUrl(), request.timeoutSecs(), request.temperature()));
            raw = SetupData.map(rawChats(virtual).getFirst());
            if (prompt != null) SetupData.put(raw, "prompt", prompt.isBlank() ? null : prompt);
            if (args != null) {
                Map<String, Object> merged = SetupData.section(raw, "args");
                args.forEach((key, value) -> merged.put(key,
                        normalizer.secret(value, Objects.toString(merged.get(key), null))));
                SetupData.put(raw, "args", merged);
            }
            Map<String, Object> validated = virtualChat(root, raw);
            normalizer.validateChat(normalizer.chat(validated));
            SetupData.put(raw, "stereo-type", target);
            if (create) models.add(raw); else models.set(position, raw);
            setChats(root, models);
            if ("default".equals(name) || "default".equals(target)) {
                putProof(root, "chat", "default".equals(target)
                        ? proof(snapshot.root(), "chat", normalizer.chat(root), testId) : Map.of());
            } else if (testId != null) {
                throw new SetupException("TEST_RECEIPT_INVALID", "chat", 409, "Receipts apply to the default chat model");
            }
            Map<String, String> entries = new LinkedHashMap<>();
            migrateKey(raw, "PRJXP_CHAT_API_KEY_", entries);
            migrateArguments(raw, entries, args);
            if (create) models.set(models.size() - 1, raw); else models.set(position, raw);
            setChats(root, models);
            return new ConfigurationStore.Update(root, entries);
        }, snapshot -> new Versioned<>(snapshot.configVersion(),
                chats(snapshot.root()).stream().filter(model -> target.equals(model.getStereoType())).findFirst().orElseThrow()));
    }

    public Versioned<Void> deleteChat(String version, String name) {
        return store.update(version, snapshot -> {
            Map<String, Object> root = snapshot.root();
            List<Object> models = rawChats(root);
            int position = find(models, name);
            if (position < 0) throw chatConflict();
            models.remove(position);
            setChats(root, models);
            if ("default".equals(name)) putProof(root, "chat", Map.of());
            return new ConfigurationStore.Update(root, Map.of());
        }, snapshot -> new Versioned<>(snapshot.configVersion(), null));
    }

    public String resolveListSecret(String requested, String stereoType, boolean embedding) {
        return store.read(snapshot -> {
            String saved;
            if (embedding) saved = embedding(snapshot.root()).apiKey();
            else saved = chats(snapshot.root()).stream().filter(model -> Objects.equals(stereoType, model.getStereoType()))
                    .map(PrjXPChatModelReference::getApiKey).filter(Objects::nonNull).findFirst().orElse(null);
            // Read the raw source, not a display mask, when resolving a stored literal.
            if ("********".equals(saved)) {
                if (embedding) saved = (String) normalizer.embeddingDraft(snapshot.root()).get("api-key");
                else {
                    int position = find(rawChats(snapshot.root()), stereoType);
                    saved = position < 0 ? null : (String) SetupData.get(SetupData.map(rawChats(snapshot.root()).get(position)), "api-key");
                }
            }
            return normalizer.resolve(normalizer.secret(requested, saved));
        });
    }

    public String resolve(String value) { return normalizer.resolve(value); }

    public Versioned<PrjXPChatModelReference> chatCandidate(String version, String name, ChatModelRequest request,
                                                          String prompt, Map<String, String> args) {
        return store.update(version, snapshot -> new ConfigurationStore.Update(snapshot.root(), Map.of()), snapshot -> {
            List<Object> models = rawChats(snapshot.root());
            int position = find(models, name);
            Map<String, Object> raw = position < 0 ? new LinkedHashMap<>() : SetupData.map(models.get(position));
            Map<String, Object> virtual = virtualChat(snapshot.root(), raw);
            normalizer.mergeChat(virtual, new ChatModelRequest("default", request.serverType(), request.modelName(),
                    request.apiKey(), request.providerUrl(), request.timeoutSecs(), request.temperature()));
            raw = SetupData.map(rawChats(virtual).getFirst());
            if (prompt != null) SetupData.put(raw, "prompt", prompt);
            if (args != null && !args.isEmpty()) {
                Map<String, Object> merged = SetupData.section(raw, "args");
                args.forEach((key, value) -> merged.put(key, normalizer.secret(value, Objects.toString(merged.get(key), null))));
                SetupData.put(raw, "args", merged);
            }
            virtual = virtualChat(snapshot.root(), raw);
            Map<String, Object> effective = normalizer.chat(virtual);
            normalizer.validateChat(effective);
            PrjXPChatModelReference candidate = new com.fasterxml.jackson.databind.ObjectMapper()
                    .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.KEBAB_CASE)
                    .convertValue(effective, PrjXPChatModelReference.class);
            candidate.setStereoType(name);
            return new Versioned<>(snapshot.configVersion(), candidate);
        });
    }

    private <T> Versioned<T> versioned(ConfigurationStore.Snapshot snapshot, Function<Map<String, Object>, T> reader) {
        return new Versioned<>(snapshot.configVersion(), reader.apply(snapshot.root()));
    }

    private EmbeddingConfigRequest embedding(Map<String, Object> root) {
        Map<String, Object> draft = normalizer.embeddingDraft(root);
        Map<String, Object> raw = SetupData.section(SetupData.section(root, "prjxp"), "embedding");
        String key = (String) SetupData.get(raw, "api-key");
        return new EmbeddingConfigRequest((String) draft.get("type"), (String) draft.get("api-base-url"),
                SetupConfigurationNormalizer.mask(key == null ? (String) draft.get("api-key") : key),
                (String) draft.get("model-name"), (Integer) draft.get("dimensions"), (Integer) draft.get("timeout"),
                (Integer) draft.get("max-segments-per-batch"), false);
    }

    private List<PrjXPChatModelReference> chats(Map<String, Object> root) {
        List<PrjXPChatModelReference> models = normalizer.configuration(root).getChatModels();
        List<Object> raw = rawChats(root);
        for (PrjXPChatModelReference model : models) {
            int position = find(raw, model.getStereoType());
            if (position >= 0) {
                Map<String, Object> map = SetupData.map(raw.get(position));
                if (SetupData.get(map, "api-key") != null) model.setApiKey((String) SetupData.get(map, "api-key"));
                if (SetupData.get(map, "prompt") != null) model.setPrompt((String) SetupData.get(map, "prompt"));
                if (SetupData.get(map, "args") != null) {
                    Map<String, String> values = new LinkedHashMap<>();
                    SetupData.section(map, "args").forEach((key, value) -> values.put(key, Objects.toString(value, null)));
                    model.setArgs(values);
                }
            }
            model.setApiKey(SetupConfigurationNormalizer.mask(model.getApiKey()));
            model.getArgs().replaceAll((key, value) -> SetupConfigurationNormalizer.mask(value));
        }
        return models;
    }

    private Map<String, Object> proof(Map<String, Object> before, String step, Map<String, Object> candidate, String token) {
        if (token != null) {
            try { return evidence.require(token, step, candidate); }
            catch (SetupException error) {
                if (!"SETUP_NOT_VERIFIED".equals(error.code())) throw error;
                throw new SetupException("TEST_RECEIPT_INVALID", step, 409, "A matching successful test receipt is required");
            }
        }
        Object stored = SetupData.section(SetupData.section(SetupData.section(before, "prjxp"), "setup-state"), "tests").get(step);
        if (stored instanceof Map<?, ?> map) {
            Map<String, Object> previous = SetupData.map(map);
            if (!Boolean.FALSE.equals(previous.get("success")) && evidence.matches(previous, candidate)) return previous;
        }
        return Map.of();
    }

    private void putProof(Map<String, Object> root, String step, Map<String, Object> proof) {
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> state = SetupData.section(prjxp, "setup-state");
        Map<String, Object> tests = SetupData.section(state, "tests");
        if (proof.isEmpty()) tests.remove(step); else tests.put(step, proof);
        SetupData.put(state, "tests", tests);
        SetupData.put(prjxp, "setup-state", state);
        SetupData.put(root, "prjxp", prjxp);
    }

    private List<Object> rawChats(Map<String, Object> root) {
        Object models = SetupData.get(SetupData.section(root, "prjxp"), "chat-models");
        return models == null ? new ArrayList<>() : new ArrayList<>((List<?>) models);
    }

    private int find(List<Object> models, String name) {
        int found = -1;
        for (int i = 0; i < models.size(); i++) {
            if (Objects.equals(name, SetupData.get(SetupData.map(models.get(i)), "stereo-type"))) {
                if (found >= 0) throw chatConflict();
                found = i;
            }
        }
        return found;
    }

    private void setChats(Map<String, Object> root, List<Object> models) {
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        SetupData.put(prjxp, "chat-models", models);
        SetupData.put(root, "prjxp", prjxp);
    }

    private Map<String, Object> virtualChat(Map<String, Object> root, Map<String, Object> raw) {
        Map<String, Object> virtual = SetupData.map(root);
        Map<String, Object> selected = SetupData.map(raw);
        SetupData.put(selected, "stereo-type", "default");
        setChats(virtual, new ArrayList<>(List.of(selected)));
        return virtual;
    }

    private void migrateArguments(Map<String, Object> raw, Map<String, String> entries, Map<String, String> requested) {
        Map<String, Object> args = SetupData.section(raw, "args");
        for (String key : new ArrayList<>(args.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean connectionFlag = "trust-all-certs".equals(lower)
                    && Set.of("true", "false").contains(Objects.toString(args.get(key)));
            if (!connectionFlag && ((requested != null && requested.containsKey(key))
                    || lower.contains("key") || lower.contains("token") || lower.contains("authorization")
                    || lower.contains("password") || lower.contains("credential") || lower.contains("secret")
                    || lower.contains("bearer"))) {
                Map<String, Object> secret = new LinkedHashMap<>();
                secret.put("api-key", Objects.toString(args.get(key), null));
                migrateKey(secret, "PRJXP_CHAT_API_KEY_", entries);
                args.put(key, secret.get("api-key"));
            }
        }
        if (!args.isEmpty()) SetupData.put(raw, "args", args);
    }

    private void migrateKey(Map<String, Object> raw, String prefix, Map<String, String> entries) {
        String value = (String) SetupData.get(raw, "api-key");
        if (value == null || value.isEmpty() || value.startsWith("${")) return;
        String key;
        try {
            EnvFileWriter writer = new EnvFileWriter(store.getEnvFilePath());
            do { key = prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT); }
            while (writer.hasKey(key) || entries.containsKey(key) || System.getProperty(key) != null || System.getenv(key) != null);
        } catch (IOException error) {
            throw new SetupException("CONFIG_READ_FAILED", null, 500, "Cannot prepare secret update");
        }
        entries.put(key, value);
        SetupData.put(raw, "api-key", "${" + key + "}");
    }

    private SetupException chatConflict() {
        return new SetupException("INVALID_CONFIG", "chat", 400, "Chat model must exist and stereotypes must be unique");
    }
}
