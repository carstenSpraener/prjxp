package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SetupPersistenceServiceTest {
    @TempDir Path directory;
    ConfigurationStore store;
    SetupTestEvidenceService evidence;
    SetupPersistenceService service;

    void start(String yaml) throws Exception {
        Map<String, Object> root = SetupData.map(new org.yaml.snakeyaml.Yaml().load(yaml));
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> lucene = SetupData.section(prjxp, "embedding-store-lucene");
        lucene.putIfAbsent("index-path", directory.resolve("index").toString());
        SetupData.put(prjxp, "embedding-store-lucene", lucene);
        SetupData.put(root, "prjxp", prjxp);
        yaml = new org.yaml.snakeyaml.Yaml().dump(root);
        Files.writeString(directory.resolve("application.yaml"), yaml);
        store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        evidence = new SetupTestEvidenceService();
        ChatModelSupplier supplier = mock(ChatModelSupplier.class);
        when(supplier.canProvide(any())).thenAnswer(invocation ->
                Set.of("ollama", "openapi").contains(
                        ((de.spraener.prjxp.common.config.PrjXPChatModelReference) invocation.getArgument(0)).getServerType()));
        service = new SetupPersistenceService(store, evidence, List.of(supplier));
    }

    String base() {
        return """
                prjxp:
                  embedding: {type: OLLAMA, api-base-url: 'http://localhost:11434', model-name: embed}
                  chat-models:
                    - {stereo-type: other, server-type: ollama, model-name: other, prompt: preserve}
                    - {stereo-type: default, server-type: ollama, model-name: chat, provider-url: 'http://localhost:11434', prompt: instructions, args: {keep: yes}}
                foreign: {keep: [1, 2]}
                """;
    }

    String embeddingTest(EmbeddingConfigRequest request) {
        var candidate = service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(), request));
        return service.recordTest(candidate, new SetupTestResult(true, "ok", 1, null, 64, null, null)).testId();
    }

    String chatTest(ChatModelRequest request) {
        var candidate = service.prepareChatTest(new SetupTestRequest<>(service.readStatus().configVersion(), request));
        return service.recordTest(candidate, new SetupTestResult(true, "ok", 1, "hello", null, null, null)).testId();
    }

    SetupStatus saveEmbedding(EmbeddingConfigRequest request, String testId) {
        return service.save(new SetupSaveRequest(service.readStatus().configVersion(), "embedding", false,
                testId, request, null, null, null));
    }

    @Test
    void verifiedSaveResumeCompletionAndRestartPreserveUnknownFieldsAndProofs() throws Exception {
        start(base());
        assertThat(service.readStatus().resumeStep()).isEqualTo("embedding");
        assertThat(service.readStatus().embedding().configured()).isTrue();
        assertThat(service.readStatus().embedding().tested()).isFalse();
        assertThatThrownBy(() -> saveEmbedding(service.readConfiguration().embedding(), null))
                .isInstanceOf(SetupException.class);
        var embedding = service.readConfiguration().embedding();
        String proof = embeddingTest(embedding);
        SetupStatus embeddingStatus = saveEmbedding(embedding, proof);
        assertThat(embeddingStatus.embedding().tested()).isTrue();
        assertThat(embeddingStatus.resumeStep()).isEqualTo("chat");
        assertThat(service.readConfiguration().optimization().vectorDimension()).isEqualTo(64);
        var chat = service.readConfiguration().chat();
        String chatProof = chatTest(chat);
        SetupStatus status = service.save(new SetupSaveRequest(service.readStatus().configVersion(), "chat",
                false, chatProof, null, chat, null, null));
        assertThat(status.chat().tested()).isTrue();
        status = service.complete(new SetupCompleteRequest(status.configVersion(), false));
        assertThat(status.completed()).isTrue();
        assertThat(status.completionMode()).isEqualTo("VERIFIED");
        String text = Files.readString(directory.resolve("application.yaml"));
        assertThat(text).contains("instructions", "preserve", "foreign", "keep", "setup-completed", "64");
        assertThat(service.complete(new SetupCompleteRequest(status.configVersion(), false)).configVersion())
                .isEqualTo(status.configVersion());
        service = new SetupPersistenceService(store(), new SetupTestEvidenceService(), suppliers());
        assertThat(service.readStatus().embedding().tested()).isTrue();
        assertThat(service.readStatus().chat().tested()).isTrue();
        assertThat(service.readStatus().restartRequired()).isFalse();
    }

    ConfigurationStore store() {
        return new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
    }
    List<ChatModelSupplier> suppliers() {
        ChatModelSupplier supplier = mock(ChatModelSupplier.class);
        when(supplier.canProvide(any())).thenReturn(true);
        return List.of(supplier);
    }

    @Test
    void matchingObservationsPersistAndFailuresInvalidateOnlyMatchingSavedCandidate() throws Exception {
        start(base());
        embeddingTest(service.readConfiguration().embedding());
        assertThat(service.readStatus().embedding().tested()).isTrue();
        EmbeddingConfigRequest changed = new EmbeddingConfigRequest(null, null, null, "different", null, null, null, false);
        var candidate = service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(), changed));
        service.recordTest(candidate, new SetupTestResult(false, "failed", 1, null, null, null, null));
        assertThat(service.readStatus().embedding().tested()).isTrue();
        candidate = service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(),
                service.readConfiguration().embedding()));
        service.recordTest(candidate, new SetupTestResult(false, "failed", 1, null, null, null, null));
        assertThat(service.readStatus().embedding().tested()).isFalse();
        assertThat(service.readStatus().embedding().problemCode()).isEqualTo("TEST_FAILED");
    }

    @Test
    void keysAreMaskedMigratedCopyOnWriteAndEnvChangesInvalidateProof() throws Exception {
        start(base().replace("model-name: embed", "model-name: embed, api-key: literal-secret"));
        assertThat(service.readConfiguration().embedding().apiKey()).isEqualTo("********");
        var request = service.readConfiguration().embedding();
        saveEmbedding(request, embeddingTest(request));
        String yaml = Files.readString(directory.resolve("application.yaml"));
        assertThat(yaml).doesNotContain("literal-secret").contains("${PRJXP_EMBEDDING_API_KEY_");
        String env = Files.readString(directory.resolve(".env"));
        assertThat(env).contains("literal-secret");
        assertThat(service.readConfiguration().embedding().apiKey()).startsWith("${PRJXP_EMBEDDING_API_KEY_");
        var updated = new EmbeddingConfigRequest(null, null, "new-secret", null, null, null, null, false);
        saveEmbedding(updated, embeddingTest(updated));
        assertThat(Files.readString(directory.resolve(".env"))).contains("literal-secret", "new-secret");
        assertThat(service.readStatus().embedding().tested()).isTrue();
        Files.writeString(directory.resolve(".env"), Files.readString(directory.resolve(".env")).replace("new-secret", "changed"));
        assertThat(service.readStatus().embedding().tested()).isFalse();
        assertThat(service.readConfiguration().toString()).doesNotContain("secret", "changed");
    }

    @Test
    void optimizationRequiresRetestAndEnforcesMeasuredDimension() throws Exception {
        start(base());
        var embedding = service.readConfiguration().embedding();
        saveEmbedding(embedding, embeddingTest(embedding));
        var wrongDimension = new OptimizationRequest(128, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(),
                "optimization", false, null, null, null, null, wrongDimension))).isInstanceOf(SetupException.class);
        var changed = new OptimizationRequest(64, 8, 3, 0, 0.5, 2.0, 3.0, 1.0, 45);
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(),
                "optimization", false, null, null, null, null, changed))).isInstanceOf(SetupException.class);
        String token = embeddingTest(new EmbeddingConfigRequest(null, null, null, null, null, 45, 8, false));
        service.save(new SetupSaveRequest(service.readStatus().configVersion(),
                "optimization", false, token, null, null, null, changed));
        assertThat(service.readConfiguration().optimization()).isEqualTo(changed);
        assertThat(service.readStatus().embedding().tested()).isTrue();
        assertThat(service.readStatus().restartRequired()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedding", "chat", "unknown"})
    void rejectsSkipOfRequiredOrUnknownStepsAndWrongPayload(String step) throws Exception {
        start(base());
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(),
                step, true, null, null, null, null, null))).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(),
                step, false, null, null, null, null, null))).isInstanceOf(SetupException.class);
    }

    @Test
    void deferAndOptionalSkipDoNotInventProofsOrModifyModels() throws Exception {
        start("prjxp: {}\n");
        assertThat(service.readStatus().embedding().configured()).isFalse();
        assertThatThrownBy(() -> service.complete(new SetupCompleteRequest(service.readStatus().configVersion(), false)))
                .isInstanceOf(SetupException.class);
        service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", true, null, null, null, null, null));
        assertThat(service.readStatus().github().skipped()).isTrue();
        assertThat(service.readConfiguration().githubEnabled()).isFalse();
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github",
                false, null, null, null, new GithubConfigRequest(null, "pat"), null)))
                .isInstanceOf(SetupException.class);
        var status = service.complete(new SetupCompleteRequest(service.readStatus().configVersion(), true));
        assertThat(status.completed()).isTrue();
        assertThat(status.completionMode()).isEqualTo("DEFERRED");
        assertThat(status.embedding().tested()).isFalse();
        assertThat(status.restartRequired()).isFalse();
        assertThat(Files.readString(directory.resolve("application.yaml"))).doesNotContain("embedding:");
    }

    @Test
    void aliasesResetAndStaleTestResponsesAreHandledExplicitly() throws Exception {
        start(base().replace("api-base-url", "apiBaseURL").replace("model-name: embed", "modelName: embed, dimensions: 64"));
        var reset = new EmbeddingConfigRequest(null, null, null, null, null, null, null, true);
        saveEmbedding(reset, embeddingTest(reset));
        String yaml = Files.readString(directory.resolve("application.yaml"));
        assertThat(yaml).contains("api-base-url", "model-name").doesNotContain("apiBaseURL", "modelName");
        assertThat(SetupData.section(SetupData.section(store.readSnapshot().root(), "prjxp"), "embedding"))
                .doesNotContainKey("dimensions");
        assertThat(service.readConfiguration().embedding().dimensions()).isNull();
        assertThatThrownBy(() -> service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(),
                new EmbeddingConfigRequest(null, null, null, null, 1, null, null, true))))
                .isInstanceOf(SetupException.class);
        var test = service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(), reset));
        Files.writeString(directory.resolve(".env"), "FOREIGN=value\n");
        assertThatThrownBy(() -> service.recordTest(test, new SetupTestResult(true, "ok", 0, null, 64, null, null)))
                .isInstanceOf(SetupException.class);
    }

    @Test
    void missingSecretRemainsEditableAndIsReportedWithoutBreakingStatus() throws Exception {
        start(base().replace("model-name: embed", "model-name: embed, api-key: '${SETUP_UNAVAILABLE_SECRET}'"));
        assertThat(service.readStatus().embedding().configured()).isFalse();
        assertThat(service.readStatus().embedding().problemCode()).isEqualTo("MISSING_SECRET");
        assertThat(service.readConfiguration().embedding().apiKey()).isEqualTo("${SETUP_UNAVAILABLE_SECRET}");
    }

    @Test
    void githubPatIsEncryptedWithCandidateKeyAndRotationDoesNotBreakExistingTokens() throws Exception {
        start(base());
        Files.writeString(directory.resolve(".env"), "FIRST_KEY=passphrase\nSECOND_KEY=other\nSAME_KEY=passphrase\n");
        var root = store.readSnapshot().root();
        var prjxp = SetupData.section(root, "prjxp");
        SetupData.put(prjxp, "hub", Map.of("enabled", true, "github-token-key-env", "FIRST_KEY"));
        SetupData.put(root, "prjxp", prjxp);
        store.update(store.readSnapshot().configVersion(), s -> new ConfigurationStore.Update(root, Map.of()));
        var cipher = new de.spraener.prjxp.mcp.hub.github.TokenCipher(
                new de.spraener.prjxp.mcp.hub.HubProperties(), new MockEnvironment(), store);
        service = new SetupPersistenceService(store, evidence, suppliers(), () -> cipher, () -> true);
        service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", false, null, null, null,
                new GithubConfigRequest("FIRST_KEY", "plaintext-pat"), null));
        assertThat(service.readConfiguration().github().defaultPat()).isEqualTo("********");
        assertThat(Files.readString(directory.resolve("application.yaml"))).doesNotContain("plaintext-pat").contains("v1:");
        var hub = store.bind(store.readSnapshot().root(), "prjxp.hub", de.spraener.prjxp.mcp.hub.HubProperties.class);
        assertThat(cipher.decrypt(hub.getGithubDefaultPat())).isEqualTo("plaintext-pat");
        var githubTest = service.prepareGithubTest(new SetupTestRequest<>(service.readStatus().configVersion(),
                new GithubConfigRequest(null, "********")));
        var result = service.recordTest(githubTest, new SetupTestResult(true, "ok", 1, "login", null, null, null));
        assertThat(result.testId()).isNull();
        assertThat(service.readStatus().github().tested()).isTrue();
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", false,
                null, null, null, new GithubConfigRequest("SECOND_KEY", null), null)))
                .isInstanceOf(SetupException.class).extracting("code").isEqualTo("KEY_ROTATION_REQUIRED");
        service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", false, null, null, null,
                new GithubConfigRequest("SAME_KEY", "********"), null));
        assertThat(cipher.decrypt(store.bind(store.readSnapshot().root(), "prjxp.hub",
                de.spraener.prjxp.mcp.hub.HubProperties.class).getGithubDefaultPat())).isEqualTo("plaintext-pat");
        service.save(new SetupSaveRequest(service.readStatus().configVersion(), "github", false, null, null, null,
                new GithubConfigRequest(null, ""), null));
        assertThat(service.readConfiguration().github().defaultPat()).isEmpty();
    }

    @Test
    void legacyIndexRequiresVerifiedCompatibilityBeforeSavingDifferentModel() throws Exception {
        start(base());
        var index = new de.spraener.prjxp.lucene.LuceneEmbeddingStore(directory.resolve("index"), 64);
        try {
            float[] vector = new float[64];
            vector[0] = 1;
            index.add(dev.langchain4j.data.embedding.Embedding.from(vector),
                    dev.langchain4j.data.segment.TextSegment.from("legacy sample"));
            var request = new EmbeddingConfigRequest(null, null, null, "compatible-different-name", null, null, null, false);
            var test = service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(), request));
            var response = new SetupTestResult(true, "ok", 1, null, 64, null, null);
            assertThatThrownBy(() -> service.recordTest(test, response)).isInstanceOf(SetupException.class);
            var model = mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
            when(model.embed(any(dev.langchain4j.data.segment.TextSegment.class))).thenReturn(
                    dev.langchain4j.model.output.Response.from(dev.langchain4j.data.embedding.Embedding.from(vector)));
            var validation = service.verifyEmbeddingIndex(test, model);
            var result = service.recordTest(test, response, validation);
            saveEmbedding(request, result.testId());
            assertThat(service.readStatus().embedding().tested()).isTrue();
            assertThat(service.readStatus().restartRequired()).isTrue();
            assertThat(index.count()).isEqualTo(1);
        } finally {
            index.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "", " ", "OPENAI"})
    void rejectsInvalidEmbeddingTypesWithoutWriting(String type) throws Exception {
        start(base());
        String original = Files.readString(directory.resolve("application.yaml"));
        assertThatThrownBy(() -> service.prepareEmbeddingTest(new SetupTestRequest<>(service.readStatus().configVersion(),
                new EmbeddingConfigRequest(type, null, null, null, null, null, null, false))))
                .isInstanceOf(SetupException.class);
        assertThat(Files.readString(directory.resolve("application.yaml"))).isEqualTo(original);
    }

    @Test
    void invalidUrlsNumbersPayloadsAndUnverifiedCompletionNeverWrite() throws Exception {
        start(base());
        String version = service.readStatus().configVersion();
        for (String url : List.of("ftp://host", "not a uri", "file:///data", "http:/missing-host")) {
            assertThatThrownBy(() -> service.prepareEmbeddingTest(new SetupTestRequest<>(version,
                    new EmbeddingConfigRequest(null, url, null, null, null, null, null, false)))).isInstanceOf(SetupException.class);
        }
        for (int number : List.of(0, -1)) {
            assertThatThrownBy(() -> service.prepareEmbeddingTest(new SetupTestRequest<>(version,
                    new EmbeddingConfigRequest(null, null, null, null, number, number, number, false))))
                    .isInstanceOf(SetupException.class);
        }
        for (double temperature : List.of(-1.0, 3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThatThrownBy(() -> service.prepareChatTest(new SetupTestRequest<>(version,
                    new ChatModelRequest("default", null, null, null, null, null, temperature)))).isInstanceOf(SetupException.class);
        }
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.complete(null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.prepareEmbeddingTest(null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.prepareChatTest(null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(version, null, false, null, null, null, null, null)))
                .isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.save(new SetupSaveRequest(version, "embedding", false, null,
                service.readConfiguration().embedding(), service.readConfiguration().chat(), null, null)))
                .isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> service.recordTest(null, null)).isInstanceOf(SetupException.class);
        assertThat(service.readStatus().completed()).isFalse();
    }
}
