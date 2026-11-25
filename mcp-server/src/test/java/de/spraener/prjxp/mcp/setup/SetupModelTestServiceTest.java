package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.*;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.embedding.EmbeddingModelFactory;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.github.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SetupModelTestServiceTest {
    @TempDir Path directory;
    ConfigurationStore store;
    SetupPersistenceService persistence;
    EmbeddingModelFactory factory;
    EmbeddingModel model;
    KIChatModelProvider chatProvider;
    KIChat chat;
    GithubApiClient github;
    SetupModelTestService tests;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), """
                prjxp:
                  embedding: {type: OLLAMA, api-base-url: 'http://127.0.0.1:1', model-name: configured}
                  chat-models:
                    - {stereo-type: default, server-type: ollama, model-name: configured, provider-url: 'http://127.0.0.1:1', prompt: retained, args: {retained: value}}
                  hub: {enabled: true, github-token-key-env: PHASE03_KEY}
                """ + "  embedding-store-lucene: {index-path: '" + directory.resolve("index") + "'}\n");
        Files.writeString(directory.resolve(".env"), "PHASE03_KEY=passphrase\n");
        store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        ChatModelSupplier supplier = mock(ChatModelSupplier.class);
        when(supplier.canProvide(any())).thenReturn(true);
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        persistence = new SetupPersistenceService(store, new SetupTestEvidenceService(), List.of(supplier), () -> cipher, () -> false);
        factory = mock(EmbeddingModelFactory.class); model = mock(EmbeddingModel.class);
        when(factory.create(any())).thenReturn(model);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0}))));
        chatProvider = mock(KIChatModelProvider.class); chat = mock(KIChat.class);
        when(chatProvider.createFreshKIChat(any())).thenReturn(chat);
        when(chat.chat("Say hello")).thenReturn("Hello");
        github = mock(GithubApiClient.class);
        ObjectProvider<GithubApiClient> optional = mock(ObjectProvider.class);
        when(optional.getIfAvailable()).thenReturn(github);
        tests = new SetupModelTestService(persistence, factory, chatProvider, optional);
    }

    @Test
    void freshTestsProduceReceiptsAndPersistOnlyMatchingSavedCandidates() {
        var embedding = tests.testEmbedding(embeddingRequest(null));
        assertThat(embedding.success()).isTrue();
        assertThat(embedding.dimensions()).isEqualTo(2);
        assertThat(embedding.testId()).isNotBlank();
        assertThat(persistence.readStatus().embedding().tested()).isTrue();
        var result = tests.testChat(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new ChatModelRequest("default", null, null, null, null, null, null)));
        assertThat(result.success()).isTrue();
        assertThat(result.testId()).isNotBlank();
        assertThat(persistence.readStatus().chat().tested()).isTrue();
        verify(chatProvider).createFreshKIChat(argThat(ref -> "retained".equals(ref.getPrompt())
                && "value".equals(ref.getArgs().get("retained"))));
        verify(factory).create(argThat(cfg -> cfg.getType() == PrjXPConfig.EmbeddingModelType.OLLAMA
                && "configured".equals(cfg.getModelName()) && cfg.getMaxSegmentsPerBatch() == 32));
        var different = tests.testEmbedding(embeddingRequest("unsaved"));
        assertThat(different.success()).isTrue();
        assertThat(Files.exists(directory.resolve("unneeded-index"))).isFalse();
        assertThat(persistence.readConfiguration().embedding().modelName()).isEqualTo("configured");
    }

    @Test
    void legacyChatCandidateOverridesPromptAndArgsWithoutMutatingSavedConfiguration() throws Exception {
        Files.writeString(directory.resolve(".env"), "PHASE03_KEY=passphrase\nCHAT_ARG=resolved-argument\n");
        var request = new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new ChatModelRequest("default", null, null, null, null, null, null));
        var result = tests.testChat(request, "submitted prompt",
                Map.of("retained", "********", "new", "${CHAT_ARG}"));
        assertThat(result.success()).isTrue();
        assertThat(persistence.readStatus().chat().tested()).isFalse();
        verify(chatProvider).createFreshKIChat(argThat(ref -> "submitted prompt".equals(ref.getPrompt())
                && "value".equals(ref.getArgs().get("retained"))
                && "resolved-argument".equals(ref.getArgs().get("new"))));
        String saved = store.read(snapshot -> snapshot.root().toString());
        assertThat(saved).doesNotContain("submitted prompt", "resolved-argument");
        clearInvocations(chatProvider);
        tests.testChat(new SetupTestRequest<>(result.configVersion(), request.configuration()), " ", Map.of());
        verify(chatProvider).createFreshKIChat(argThat(ref -> ref.getPrompt() == null
                && "value".equals(ref.getArgs().get("retained"))));
        assertThatThrownBy(() -> tests.testChat(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                request.configuration()), null, Map.of("absent", "********"))).isInstanceOf(SetupException.class)
                .extracting("code").isEqualTo("MISSING_SECRET");
    }

    @Test
    void verifiedLifecycleTestsSavesAndCompletesWithMeasuredDimensionAndRestartNotice() {
        var embeddingRequest = embeddingRequest(null);
        var embedding = tests.testEmbedding(embeddingRequest);
        var afterEmbedding = persistence.save(new SetupSaveRequest(embedding.configVersion(), "embedding", false,
                embedding.testId(), embeddingRequest.configuration(), null, null, null));
        assertThat(persistence.readConfiguration().optimization().vectorDimension()).isEqualTo(2);
        var chatRequest = new ChatModelRequest("default", null, null, null, null, null, null);
        var chat = tests.testChat(new SetupTestRequest<>(afterEmbedding.configVersion(), chatRequest));
        var afterChat = persistence.save(new SetupSaveRequest(chat.configVersion(), "chat", false, chat.testId(),
                null, chatRequest, null, null));
        var completed = persistence.complete(new SetupCompleteRequest(afterChat.configVersion(), false));
        assertThat(completed.completed()).isTrue();
        assertThat(completed.completionMode()).isEqualTo("VERIFIED");
        assertThat(completed.restartRequired()).isTrue();
    }

    @Test
    void malformedVectorsAndChatResponsesAreFailuresAndInvalidateOnlyMatchingProofs() {
        tests.testEmbedding(embeddingRequest(null));
        for (float[] vector : new float[][]{{}, {0, 0}, {Float.NaN}, {Float.POSITIVE_INFINITY}}) {
            when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(vector))));
            var result = tests.testEmbedding(embeddingRequest(null));
            assertThat(result.success()).isFalse();
            assertThat(result.testId()).isNull();
        }
        when(model.embedAll(anyList())).thenReturn(null);
        assertThat(tests.testEmbedding(embeddingRequest(null)).success()).isFalse();
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of()));
        assertThat(tests.testEmbedding(embeddingRequest(null)).success()).isFalse();
        assertThat(persistence.readStatus().embedding().tested()).isFalse();
        when(chat.chat(anyString())).thenReturn(" ");
        assertThat(tests.testChat(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new ChatModelRequest(null, null, null, null, null, null, null))).success()).isFalse();
    }

    @Test
    void dimensionMismatchRejectsProviderResult() {
        var request = new EmbeddingConfigRequest(null, null, null, null, 3, null, null, false);
        assertThat(tests.testEmbedding(new SetupTestRequest<>(persistence.readStatus().configVersion(), request)).success()).isFalse();
    }

    @Test
    void knownProviderFailuresAreRedactedButInternalFailuresPropagateAndInterruptIsPreserved() {
        when(model.embedAll(anyList())).thenThrow(new dev.langchain4j.exception.HttpException(401, "private-secret"));
        var result = tests.testEmbedding(embeddingRequest(null));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).doesNotContain("private-secret");
        doThrow(new IllegalStateException("internal-failure")).when(model).embedAll(anyList());
        assertThatThrownBy(() -> tests.testEmbedding(embeddingRequest(null))).isInstanceOf(IllegalStateException.class);
        doThrow(new RuntimeException(new InterruptedException("private-secret"))).when(model).embedAll(anyList());
        try {
            assertThat(tests.testEmbedding(embeddingRequest(null)).success()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void concurrentConfigurationChangePreventsReceiptPublicationAndDoesNotHoldLockDuringProviderCall() {
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            java.util.concurrent.CompletableFuture.runAsync(() ->
                    store.update(persistence.readStatus().configVersion(), snapshot -> {
                        var root = snapshot.root(); root.put("external-change", true);
                        return new ConfigurationStore.Update(root, Map.of());
                    })).get(2, java.util.concurrent.TimeUnit.SECONDS);
            return Response.from(List.of(Embedding.from(new float[]{1, 0})));
        });
        assertThatThrownBy(() -> tests.testEmbedding(embeddingRequest(null))).isInstanceOf(SetupException.class)
                .extracting("code").isEqualTo("CONFIG_CHANGED");
        assertThat(persistence.readStatus().embedding().tested()).isFalse();
    }

    @Test
    void githubUsesPreparedPlaintextButNeverCreatesAModelReceipt() {
        when(github.testToken(any())).thenReturn(new GithubUserResult(true, "alice", "Verified", 10));
        var result = tests.testGithub(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new GithubConfigRequest("PHASE03_KEY", "candidate-token")));
        assertThat(result.success()).isTrue();
        assertThat(result.response()).isEqualTo("alice");
        assertThat(result.testId()).isNull();
        verify(github).testToken(new GithubConfigRequest("PHASE03_KEY", "candidate-token"));
    }

    @Test
    void legacyIndexCompatibilityIsRequiredAndInvalidSampleResponsesAreNormalTestFailures() {
        var index = new de.spraener.prjxp.lucene.LuceneEmbeddingStore(directory.resolve("index"), 2);
        try {
            index.add(Embedding.from(new float[]{1, 0}), dev.langchain4j.data.segment.TextSegment.from("indexed-content"));
            assertThat(tests.testEmbedding(embeddingRequest(null)).success()).isTrue();
            assertThat(index.count()).isEqualTo(1);
            when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{0, 1}))));
            assertThatThrownBy(() -> tests.testEmbedding(embeddingRequest(null))).isInstanceOf(SetupException.class)
                    .extracting("code").isEqualTo("INDEX_REBUILD_REQUIRED");
            for (float[] invalid : new float[][]{{Float.NaN, 0}, {1, 0, 0}}) {
                doAnswer(invocation -> {
                    List<dev.langchain4j.data.segment.TextSegment> segments = invocation.getArgument(0);
                    return Response.from(List.of(Embedding.from(segments.getFirst().text().equals("test")
                            ? new float[]{1, 0} : invalid)));
                }).when(model).embedAll(anyList());
                assertThat(tests.testEmbedding(embeddingRequest(null)).success()).isFalse();
            }
            assertThat(index.count()).isEqualTo(1);
        } finally { index.close(); }
    }

    @Test
    void explicitNewSecretCanBeTestedWhenOldSavedReferenceIsUnavailable() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), Files.readString(directory.resolve("application.yaml"))
                .replace("type: OLLAMA", "type: OPEN_AI, api-key: '${UNAVAILABLE_PHASE03_SECRET}'"));
        var result = tests.testEmbedding(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new EmbeddingConfigRequest(null, null, "replacement-key", null, null, null, null, false)));
        assertThat(result.success()).isTrue();
        assertThat(result.testId()).isNotBlank();
        assertThat(persistence.readStatus().embedding().tested()).isFalse();
    }

    @Test
    void testingMaskedGithubDefaultUsesStoredCipherKeyWithoutApplyingSaveRotationPolicy() throws Exception {
        Files.writeString(directory.resolve(".env"), "PHASE03_KEY=passphrase\nNEW_PHASE03_KEY=different-passphrase\n");
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        String encrypted = cipher.encrypt("default-pat", "PHASE03_KEY");
        store.update(persistence.readStatus().configVersion(), snapshot -> {
            var root = snapshot.root();
            var prjxp = SetupData.section(root, "prjxp");
            var hub = SetupData.section(prjxp, "hub");
            hub.put("github-default-pat", encrypted);
            prjxp.put("hub", hub); root.put("prjxp", prjxp);
            return new ConfigurationStore.Update(root, Map.of());
        });
        when(github.testToken(any())).thenReturn(new GithubUserResult(true, "alice", "Verified", 10));
        var result = tests.testGithub(new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new GithubConfigRequest("NEW_PHASE03_KEY", "********")));
        assertThat(result.success()).isTrue();
        verify(github).testToken(new GithubConfigRequest("NEW_PHASE03_KEY", "default-pat"));
        assertThat(persistence.readConfiguration().github().tokenKeyEnv()).isEqualTo("PHASE03_KEY");
        assertThat(persistence.readStatus().github().tested()).isFalse();
    }

    private SetupTestRequest<EmbeddingConfigRequest> embeddingRequest(String name) {
        return new SetupTestRequest<>(persistence.readStatus().configVersion(),
                new EmbeddingConfigRequest(null, null, null, name, null, null, null, false));
    }
}
