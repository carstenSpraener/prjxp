package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import de.spraener.prjxp.common.config.EnvFileWriter;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.github.GithubTokenResolver;
import de.spraener.prjxp.mcp.hub.github.TokenCipher;
import de.spraener.prjxp.mcp.hub.github.TokenCipherException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

@Service
public class SetupPersistenceService {
    private final ConfigurationStore store;
    private final SetupTestEvidenceService evidence;
    private final SetupConfigurationNormalizer normalizer;
    private final byte[] activated;
    private final Supplier<TokenCipher> cipher;
    private final GithubTokenResolver githubTokens;
    private final Supplier<Boolean> projectTokens;
    private final SetupIndexGuard index;

    @Autowired
    public SetupPersistenceService(ConfigurationStore store, SetupTestEvidenceService evidence,
                                   List<ChatModelSupplier> suppliers, ObjectProvider<TokenCipher> cipher,
                                   ObjectProvider<HubProjectRegistry> registry) {
        this(store, evidence, suppliers, cipher::getIfAvailable, () -> {
            HubProjectRegistry hub = registry.getIfAvailable();
            return hub != null && hub.availableProjects().stream()
                    .map(hub::definitionOf).anyMatch(definition -> definition.getGitToken() != null);
        });
    }

    SetupPersistenceService(ConfigurationStore store, SetupTestEvidenceService evidence, List<ChatModelSupplier> suppliers) {
        this(store, evidence, suppliers, () -> null, () -> false);
    }

    SetupPersistenceService(ConfigurationStore store, SetupTestEvidenceService evidence,
                            List<ChatModelSupplier> suppliers, Supplier<TokenCipher> cipher, Supplier<Boolean> projectTokens) {
        this.store = store;
        this.evidence = evidence;
        this.normalizer = new SetupConfigurationNormalizer(store, suppliers);
        this.cipher = cipher;
        this.githubTokens = new GithubTokenResolver(store, cipher);
        this.projectTokens = projectTokens;
        this.index = new SetupIndexGuard(store);
        this.activated = SetupData.canonical(normalizer.runtime(store.activatedRoot()));
    }

    public Path getApplicationYamlPath() { return store.getApplicationYamlPath(); }
    public Path getEnvFilePath() { return store.getEnvFilePath(); }
    public SetupStatus readStatus() { return store.read(this::status); }

    public SetupConfiguration readConfiguration() {
        return store.read(this::configuration);
    }

    private SetupConfiguration configuration(ConfigurationStore.Snapshot snapshot) {
        Map<String, Object> root = snapshot.root();
        Map<String, Object> embedding = normalizer.embeddingDraft(root), chat = normalizer.chatDraft(root);
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> rawEmbedding = SetupData.section(prjxp, "embedding");
        String embeddingKey = (String) SetupData.get(rawEmbedding, "api-key");
        if (embeddingKey == null) embeddingKey = (String) embedding.get("api-key");
        String chatKey = rawChatKey(root, (String) chat.get("api-key"));
        var hub = normalizer.hub(root);
        String githubPat = (String) SetupData.get(SetupData.section(prjxp, "hub"), "github-default-pat");
        if (githubPat == null) githubPat = hub.getGithubDefaultPat();
        return new SetupConfiguration(status(snapshot),
                new EmbeddingConfigRequest((String) embedding.get("type"), (String) embedding.get("api-base-url"),
                        SetupConfigurationNormalizer.mask(embeddingKey), (String) embedding.get("model-name"),
                        (Integer) embedding.get("dimensions"), (Integer) embedding.get("timeout"),
                        (Integer) embedding.get("max-segments-per-batch"), false),
                new ChatModelRequest("default", (String) chat.get("server-type"), (String) chat.get("model-name"),
                        SetupConfigurationNormalizer.mask(chatKey), (String) chat.get("provider-url"),
                        (Integer) chat.get("timeout-secs"), ((Number) chat.get("temperature")).doubleValue()),
                new GithubConfigRequest(hub.getGithubTokenKeyEnv(), SetupConfigurationNormalizer.mask(githubPat)),
                normalizer.optimization(root), hub.isEnabled(), keyAvailable(hub.getGithubTokenKeyEnv()));
    }

    private String rawChatKey(Map<String, Object> root, String fallback) {
        Object models = SetupData.get(SetupData.section(root, "prjxp"), "chat-models");
        if (models instanceof List<?> list) {
            for (Object model : list) {
                Map<String, Object> raw = SetupData.map(model);
                if ("default".equals(SetupData.get(raw, "stereo-type"))) {
                    Object key = SetupData.get(raw, "api-key");
                    return key == null ? fallback : (String) key;
                }
            }
        }
        return fallback;
    }

    public SetupStatus save(SetupSaveRequest request) {
        validate(request);
        return store.update(request.configVersion(), current -> {
            Map<String, Object> root = current.root();
            Map<String, String> envEntries = new LinkedHashMap<>();
            Map<String, Object> state = state(root);
            if (!request.skip()) {
                switch (request.step()) {
                    case "embedding" -> {
                        normalizer.mergeEmbedding(root, request.embedding());
                        Map<String, Object> proof = proof(root, current.root(), "embedding", request.testId());
                        index.require(root, normalizer.embedding(root), proof);
                        dimension(root, proof, true);
                        persistProof(root, "embedding", proof);
                        migrate(root, "embedding", envEntries);
                    }
                    case "chat" -> {
                        normalizer.mergeChat(root, request.chat());
                        persistProof(root, "chat", proof(root, current.root(), "chat", request.testId()));
                        migrate(root, "chat", envEntries);
                    }
                    case "optimization" -> {
                        normalizer.mergeOptimization(root, request.optimization());
                        Map<String, Object> proof = proof(root, current.root(), "embedding", request.testId());
                        index.require(root, normalizer.embedding(root), proof);
                        dimension(root, proof, false);
                        persistProof(root, "embedding", proof);
                    }
                    case "github" -> saveGithub(root, request.github());
                    default -> throw SetupConfigurationNormalizer.invalid();
                }
                state = state(root);
            }
            if ("optimization".equals(request.step()) && request.skip()) {
                Map<String, Object> existing = tests(root);
                if (existing.get("embedding") instanceof Map<?, ?> proof) {
                    index.require(root, normalizer.embedding(root), SetupData.map(proof));
                    dimension(root, SetupData.map(proof), false);
                }
            }
            Map<String, Object> saved = SetupData.section(state, "saved-steps");
            saved.put(request.step(), request.skip() ? "SKIPPED" : "SAVED");
            SetupData.put(state, "saved-steps", saved);
            setState(root, state);
            return new ConfigurationStore.Update(root, envEntries);
        }, this::status);
    }

    public SetupStatus complete(SetupCompleteRequest request) {
        if (request == null) throw SetupConfigurationNormalizer.invalid();
        return store.update(request.configVersion(), current -> {
            Map<String, Object> root = current.root();
            if (!request.defer()) {
                SetupStatus status = status(current);
                if (!status.embedding().tested() || !status.chat().tested()) {
                    throw new SetupException("SETUP_NOT_VERIFIED", null, 409, "Embedding and chat require matching successful tests");
                }
                Map<String, Object> proof = SetupData.map(tests(root).get("embedding"));
                index.require(root, normalizer.embedding(root), proof);
                int measured = ((Number) proof.get("dimensions")).intValue();
                if (normalizer.optimization(root).vectorDimension() != measured)
                    throw new SetupException("INDEX_REBUILD_REQUIRED", "embedding", 409, "Save the verified vector dimension before completion");
            }
            Map<String, Object> prjxp = SetupData.section(root, "prjxp");
            SetupData.put(prjxp, "setup-completed", true);
            SetupData.put(root, "prjxp", prjxp);
            Map<String, Object> state = state(root);
            SetupData.put(state, "completion-mode", request.defer() ? "DEFERRED" : "VERIFIED");
            setState(root, state);
            return new ConfigurationStore.Update(root, Map.of());
        }, this::status);
    }

    public PreparedTest prepareEmbeddingTest(SetupTestRequest<EmbeddingConfigRequest> request) {
        if (request == null) throw SetupConfigurationNormalizer.invalid();
        var snapshot = checked(request.configVersion());
        Map<String, Object> root = normalizer.mergeEmbedding(snapshot.root(), request.configuration());
        return new PreparedTest(snapshot.configVersion(), "embedding", normalizer.embedding(root));
    }

    public PreparedTest prepareChatTest(SetupTestRequest<ChatModelRequest> request) {
        return prepareChatTest(request, null, null);
    }

    public PreparedTest prepareChatTest(SetupTestRequest<ChatModelRequest> request,
                                        String prompt, Map<String, String> args) {
        if (request == null) throw SetupConfigurationNormalizer.invalid();
        var snapshot = checked(request.configVersion());
        Map<String, Object> root = normalizer.mergeChat(snapshot.root(), request.configuration(), prompt, args);
        return new PreparedTest(snapshot.configVersion(), "chat", normalizer.chat(root));
    }

    public PreparedTest prepareGithubTest(SetupTestRequest<GithubConfigRequest> request) {
        if (request == null || request.configuration() == null) throw SetupConfigurationNormalizer.invalid();
        var snapshot = checked(request.configVersion());
        Map<String, Object> root = snapshot.root();
        var hub = normalizer.hub(root);
        Map<String, Object> candidate = new LinkedHashMap<>();
        try { candidate.put("token", githubTokens.forTest(request.configuration(), hub)); }
        catch (TokenCipherException error) {
            throw new SetupException(error.code(), "github", 400, "Cannot resolve GitHub token");
        }
        candidate.put("key-name", request.configuration().tokenKeyEnv() == null
                ? hub.getGithubTokenKeyEnv() : request.configuration().tokenKeyEnv());
        return new PreparedTest(snapshot.configVersion(), "github", candidate);
    }

    private ConfigurationStore.Snapshot checked(String version) {
        return store.update(version, current -> new ConfigurationStore.Update(current.root(), Map.of()));
    }

    public SetupTestResult recordTest(PreparedTest prepared, SetupTestResult result) {
        return recordTest(prepared, result, null);
    }

    public SetupIndexGuard.Validation verifyEmbeddingIndex(PreparedTest prepared,
                                                           dev.langchain4j.model.embedding.EmbeddingModel model) {
        var snapshot = checked(prepared.configVersion());
        return index.verify(snapshot.root(), prepared.effective(), model);
    }

    public SetupTestResult recordTest(PreparedTest prepared, SetupTestResult result, SetupIndexGuard.Validation validation) {
        if (prepared == null || result == null) throw SetupConfigurationNormalizer.invalid();
        if (result.success()) {
            if ("embedding".equals(prepared.step())) {
                SetupConfigurationNormalizer.positive(result.dimensions());
                Object requested = prepared.effective().get("dimensions");
                if (requested != null && !requested.equals(result.dimensions())) throw SetupConfigurationNormalizer.invalid();
            } else SetupConfigurationNormalizer.text(result.response());
        }
        String[] token = {null};
        var updated = store.update(prepared.configVersion(), current -> {
            Map<String, Object> root = current.root();
            Map<String, Object> effective = null;
            try { effective = effective(root, prepared.step()); }
            catch (SetupException error) {
                if (!"MISSING_SECRET".equals(error.code())) throw error;
                org.slf4j.LoggerFactory.getLogger(getClass()).debug("Saved candidate is unavailable; retaining only the new test receipt");
            }
            Map<String, Object> proof;
            if (result.success()) {
                SetupIndexGuard.Validation checkedIndex = validation;
                if ("embedding".equals(prepared.step())) {
                    if (checkedIndex == null) checkedIndex = index.emptyValidation(root, prepared.effective());
                    index.requireValidation(root, prepared.effective(), checkedIndex);
                }
                if ("github".equals(prepared.step())) {
                    proof = evidence.createProof(prepared.effective(), null);
                    proof.put("login", result.response());
                } else {
                    token[0] = evidence.issue(prepared.step(), prepared.effective(), result.dimensions());
                    proof = evidence.require(token[0], prepared.step(), prepared.effective());
                    if (checkedIndex != null) proof = evidence.attachIndex(token[0], checkedIndex);
                }
            } else {
                proof = evidence.createProof(prepared.effective(), null);
                proof.put("success", false);
            }
            if (effective != null && Arrays.equals(SetupData.canonical(effective), SetupData.canonical(prepared.effective()))) {
                persistProof(root, prepared.step(), proof);
            }
            return new ConfigurationStore.Update(root, Map.of());
        });
        return new SetupTestResult(result.success(), result.message(), result.latencyMs(), result.response(),
                result.dimensions(), token[0], updated.configVersion());
    }

    private Map<String, Object> effective(Map<String, Object> root, String step) {
        return switch (step) {
            case "embedding" -> normalizer.embedding(root);
            case "chat" -> normalizer.chat(root);
            case "github" -> githubEffective(root);
            default -> throw SetupConfigurationNormalizer.invalid();
        };
    }

    private Map<String, Object> proof(Map<String, Object> root, Map<String, Object> before, String step, String token) {
        Map<String, Object> candidate = effective(root, step);
        if (token != null) return evidence.require(token, step, candidate);
        Object stored = tests(before).get(step);
        if (stored instanceof Map<?, ?> map) {
            Map<String, Object> proof = SetupData.map(map);
            if (!Boolean.FALSE.equals(proof.get("success")) && evidence.matches(proof, candidate)) return proof;
        }
        throw new SetupException("SETUP_NOT_VERIFIED", step, 409, "A matching successful model test is required");
    }

    private void dimension(Map<String, Object> root, Map<String, Object> proof, boolean automatic) {
        Object measured = proof.get("dimensions");
        SetupConfigurationNormalizer.positive(measured);
        int dimension = ((Number) measured).intValue();
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> lucene = SetupData.section(prjxp, "embedding-store-lucene");
        Object configured = SetupData.get(lucene, "vector-dimension");
        if (!automatic && configured != null && ((Number) configured).intValue() != dimension) {
            throw new SetupException("INVALID_REQUEST", "optimization", 400, "Lucene dimension must match the verified vector");
        }
        SetupData.put(lucene, "vector-dimension", dimension);
        SetupData.put(prjxp, "embedding-store-lucene", lucene);
        SetupData.put(root, "prjxp", prjxp);
    }

    private void migrate(Map<String, Object> root, String step, Map<String, String> entries) {
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        if ("embedding".equals(step)) {
            Map<String, Object> raw = SetupData.section(prjxp, "embedding");
            migrateKey(raw, "PRJXP_EMBEDDING_API_KEY_", entries);
            SetupData.put(prjxp, "embedding", raw);
        } else {
            List<?> models = (List<?>) SetupData.get(prjxp, "chat-models");
            List<Object> changed = new ArrayList<>();
            for (Object model : models) {
                Map<String, Object> raw = SetupData.map(model);
                if ("default".equals(SetupData.get(raw, "stereo-type"))) migrateKey(raw, "PRJXP_CHAT_API_KEY_", entries);
                changed.add(raw);
            }
            SetupData.put(prjxp, "chat-models", changed);
        }
        SetupData.put(root, "prjxp", prjxp);
    }

    private void migrateKey(Map<String, Object> raw, String prefix, Map<String, String> entries) {
        String value = (String) SetupData.get(raw, "api-key");
        if (value == null || value.isEmpty() || value.startsWith("${")) return;
        String key;
        try {
            EnvFileWriter writer = new EnvFileWriter(store.getEnvFilePath());
            do { key = prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT); }
            while (writer.hasKey(key) || System.getProperty(key) != null || System.getenv(key) != null);
        } catch (IOException error) { throw new SetupException("CONFIG_READ_FAILED", null, 500, "Cannot prepare secret update"); }
        entries.put(key, value);
        SetupData.put(raw, "api-key", "${" + key + "}");
    }

    private void saveGithub(Map<String, Object> root, GithubConfigRequest request) {
        var configured = normalizer.hub(root);
        if (!configured.isEnabled()) throw new SetupException("HUB_DISABLED", "github", 409, "GitHub hub is disabled");
        String name = request.tokenKeyEnv() == null ? configured.getGithubTokenKeyEnv() : request.tokenKeyEnv();
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw SetupConfigurationNormalizer.invalid();
        Map<String, Object> oldHub = SetupData.section(SetupData.section(root, "prjxp"), "hub");
        String oldPat = (String) SetupData.get(oldHub, "github-default-pat");
        if (oldPat == null) oldPat = configured.getGithubDefaultPat();
        if (!name.equals(configured.getGithubTokenKeyEnv())
                && ((oldPat != null && normalizer.resolve(oldPat).startsWith("v1:")) || projectTokens.get())) {
            String oldKey = normalizer.resolve("${" + configured.getGithubTokenKeyEnv() + "}");
            String newKey = normalizer.resolve("${" + name + "}");
            if (!MessageDigest.isEqual(oldKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    newKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new SetupException("KEY_ROTATION_REQUIRED", "github", 409, "Existing tokens require explicit key rotation");
            }
        }
        String pat = normalizer.secret(request.defaultPat(), oldPat);
        if (pat != null && !pat.isEmpty()) {
            if (!keyAvailable(name)) throw SetupConfigurationNormalizer.missing();
            TokenCipher tokenCipher = cipher.get();
            if (tokenCipher == null) throw new SetupException("HUB_DISABLED", "github", 409, "GitHub token encryption is unavailable");
            try {
                boolean preserved = request.defaultPat() == null || "********".equals(request.defaultPat());
                String plaintext = githubTokens.resolve(pat, preserved ? configured.getGithubTokenKeyEnv() : name);
                pat = pat.startsWith("${") || (pat.startsWith("v1:") && name.equals(configured.getGithubTokenKeyEnv()))
                        ? pat : tokenCipher.encrypt(plaintext, name);
            } catch (TokenCipherException error) {
                throw new SetupException(error.code(), "github", 400, "Cannot encrypt or decrypt GitHub token");
            }
        }
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> hub = SetupData.section(prjxp, "hub");
        SetupData.put(hub, "github-token-key-env", name);
        SetupData.put(hub, "github-default-pat", pat);
        SetupData.put(prjxp, "hub", hub);
        SetupData.put(root, "prjxp", prjxp);
    }

    private boolean keyAvailable(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) return false;
        String value = normalizer.resolve("${" + name + ":}");
        return value != null && !value.isBlank();
    }

    private Map<String, Object> githubEffective(Map<String, Object> root) {
        var hub = normalizer.hub(root);
        String pat = hub.getGithubDefaultPat();
        try { pat = githubTokens.resolve(pat, hub.getGithubTokenKeyEnv()); }
        catch (TokenCipherException error) {
            throw new SetupException(error.code(), "github", 400, "Cannot resolve stored GitHub token");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", pat);
        result.put("key-name", hub.getGithubTokenKeyEnv());
        return result;
    }

    private SetupStatus status(ConfigurationStore.Snapshot snapshot) {
        Map<String, Object> root = snapshot.root(), state = state(root), saved = SetupData.section(state, "saved-steps");
        StepStatus embedding = step(root, "embedding", true, saved);
        StepStatus chat = step(root, "chat", true, saved);
        var hub = normalizer.hub(root);
        boolean skippedGithub = "SKIPPED".equals(saved.get("github"));
        boolean githubReady = hub.isEnabled() && keyAvailable(hub.getGithubTokenKeyEnv());
        StepStatus github = new StepStatus(githubReady, false, false, skippedGithub,
                skippedGithub || githubReady ? null : hub.isEnabled() ? "MISSING_SECRET" : "HUB_DISABLED",
                skippedGithub || githubReady ? null : "GitHub token encryption is unavailable", null);
        if (githubReady) {
            try {
                Map<String, Object> candidate = githubEffective(root);
                Object stored = tests(root).get("github");
                Map<String, Object> proof = stored instanceof Map<?, ?> map ? SetupData.map(map) : null;
                boolean tested = proof != null && !Boolean.FALSE.equals(proof.get("success")) && evidence.matches(proof, candidate);
                github = new StepStatus(true, tested, false, skippedGithub, null, null,
                        tested ? Objects.toString(SetupData.get(proof, "tested-at"), null) : null);
            } catch (SetupException error) {
                github = new StepStatus(false, false, false, skippedGithub, error.code(), error.getMessage(), null);
            }
        }
        boolean skippedOptimization = "SKIPPED".equals(saved.get("optimization"));
        StepStatus optimization;
        try {
            normalizer.validateOptimization(normalizer.optimization(root));
            optimization = new StepStatus(true, false, false, skippedOptimization, null, null, null);
        } catch (SetupException error) {
            optimization = new StepStatus(false, false, false, skippedOptimization, error.code(), error.getMessage(), null);
        }
        String resume = !embedding.configured() || !embedding.tested() ? "embedding"
                : !chat.configured() || !chat.tested() ? "chat"
                : List.of("embedding", "github", "chat", "optimization").stream()
                        .filter(step -> !saved.containsKey(step)).findFirst().orElse("optimization");
        Object mode = SetupData.get(state, "completion-mode");
        return new SetupStatus(snapshot.configVersion(), normalizer.configuration(root).isSetupCompleted(),
                mode == null ? "NONE" : mode.toString(), !Arrays.equals(activated, SetupData.canonical(normalizer.runtime(root))),
                resume, embedding, github, chat, optimization);
    }

    private StepStatus step(Map<String, Object> root, String step, boolean required, Map<String, Object> saved) {
        try {
            Map<String, Object> prjxp = SetupData.section(root, "prjxp");
            if ("embedding".equals(step) && SetupData.section(prjxp, "embedding").isEmpty()
                    || "chat".equals(step) && normalizer.chat(root).get("model-name") == null) {
                return new StepStatus(false, false, required, false, "MISSING_CONFIG", "Configuration is missing", null);
            }
            Map<String, Object> candidate = effective(root, step);
            if ("embedding".equals(step)) normalizer.validateEmbedding(candidate); else normalizer.validateChat(candidate);
            Object stored = tests(root).get(step);
            Map<String, Object> proof = stored instanceof Map<?, ?> map ? SetupData.map(map) : null;
            boolean matching = proof != null && evidence.matches(proof, candidate);
            boolean failed = matching && Boolean.FALSE.equals(proof.get("success"));
            boolean tested = matching && !failed;
            if (tested && "embedding".equals(step)) index.require(root, candidate, proof);
            return new StepStatus(true, tested, required, "SKIPPED".equals(saved.get(step)),
                    tested ? null : failed ? "TEST_FAILED" : "NOT_TESTED", tested ? null : "A successful model test is required",
                    tested ? Objects.toString(SetupData.get(proof, "tested-at"), null) : null);
        } catch (SetupException error) {
            return new StepStatus(false, false, required, false, error.code(), error.getMessage(), null);
        }
    }

    private static Map<String, Object> state(Map<String, Object> root) {
        return SetupData.section(SetupData.section(root, "prjxp"), "setup-state");
    }
    private static Map<String, Object> tests(Map<String, Object> root) { return SetupData.section(state(root), "tests"); }
    private static void setState(Map<String, Object> root, Map<String, Object> state) {
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        SetupData.put(prjxp, "setup-state", state);
        SetupData.put(root, "prjxp", prjxp);
    }
    private static void persistProof(Map<String, Object> root, String step, Map<String, Object> proof) {
        Map<String, Object> state = state(root), tests = SetupData.section(state, "tests");
        tests.put(step, proof);
        SetupData.put(state, "tests", tests);
        setState(root, state);
    }
    private static void validate(SetupSaveRequest request) {
        if (request == null || request.step() == null || !Set.of("embedding", "chat", "github", "optimization").contains(request.step()))
            throw SetupConfigurationNormalizer.invalid();
        int count = (request.embedding() == null ? 0 : 1) + (request.chat() == null ? 0 : 1)
                + (request.github() == null ? 0 : 1) + (request.optimization() == null ? 0 : 1);
        if (request.skip()) {
            if (!Set.of("github", "optimization").contains(request.step()) || count != 0 || request.testId() != null)
                throw SetupConfigurationNormalizer.invalid();
        } else if (count != 1 || switch (request.step()) {
            case "embedding" -> request.embedding() == null;
            case "chat" -> request.chat() == null;
            case "github" -> request.github() == null;
            default -> request.optimization() == null;
        }) throw SetupConfigurationNormalizer.invalid();
    }

    public static final class PreparedTest {
        private final String configVersion, step;
        private final Map<String, Object> effective;
        private PreparedTest(String version, String step, Map<String, Object> effective) {
            this.configVersion = version; this.step = step; this.effective = SetupData.map(effective);
        }
        public String configVersion() { return configVersion; }
        public String step() { return step; }
        public Map<String, Object> effective() { return SetupData.map(effective); }
        @Override public String toString() { return "PreparedSetupTest[redacted]"; }
    }
}
