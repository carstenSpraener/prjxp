package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.config.EnvFileWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class GlobalsConfigurationServiceTest {
    @TempDir Path directory;
    GlobalsTestFixture f;

    @BeforeEach void start() throws Exception { f = new GlobalsTestFixture(directory); }

    @Test void partialEmbeddingCanonicalizesAliasesWithoutLosingForeignOrLegacyValues() {
        var saved = f.globals.updateEmbedding(f.version(), f.embeddingChange("changed"), null);
        assertThat(saved.value().modelName()).isEqualTo("changed");
        assertThat(saved.value().apiBaseURL()).isEqualTo("http://localhost:11434");
        assertThat(saved.value().resetDimensions()).isFalse();
        var embedding = embedding();
        assertThat(embedding).containsKeys("model-name", "api-base-url", "trust-all-certs", "custom-headers", "custom-query-params")
                .doesNotContainKeys("modelName", "apiBaseURL");
        assertThat(f.root()).containsKey("foreign");
        assertThat(SetupData.section(prjxp(), "embedding-model")).containsEntry("model-name", "untouched");
        assertThat(f.setup.readStatus().completed()).isTrue();
        assertThat(f.setup.readStatus().restartRequired()).isTrue();
        assertThat(f.setup.readStatus().completionMode()).isEqualTo("DEFERRED");
        assertThat(f.setup.readStatus().embedding().tested()).isFalse();
        assertThat(tests()).containsKey("github");
    }

    @Test void testReceiptAttachesCorrectProofAndAutomaticDimensionThenUnchangedProofSurvives() {
        var change = f.embeddingChange("tested");
        var receipt = f.embeddingReceipt(change);
        assertThat(f.setup.readStatus().embedding().tested()).isFalse();
        var saved = f.globals.updateEmbedding(receipt.configVersion(), change, receipt.testId());
        assertThat(saved.value().modelName()).isEqualTo("tested");
        assertThat(f.setup.readStatus().embedding().tested()).isTrue();
        assertThat(f.setup.readConfiguration().optimization().vectorDimension()).isEqualTo(64);
        var proof = tests().get("embedding");
        f.globals.updateEmbedding(saved.configVersion(), f.embeddingChange(null), null);
        assertThat(tests().get("embedding")).isEqualTo(proof);
        f.globals.updateEmbedding(f.version(), f.embeddingChange("untested"), null);
        assertThat(tests()).doesNotContainKey("embedding");
        assertThat(f.setup.readStatus().completed()).isTrue();
        assertThat(f.setup.readStatus().embedding().tested()).isFalse();
    }

    @Test void receiptForOtherCandidateAndForgedReceiptFailWithoutWrites() {
        var receipt = f.embeddingReceipt(f.embeddingChange("candidate"));
        var before = f.root();
        assertCode("TEST_RECEIPT_INVALID", () -> f.globals.updateEmbedding(f.version(), f.embeddingChange("wrong"), receipt.testId()));
        assertCode("TEST_RECEIPT_INVALID", () -> f.globals.updateEmbedding(f.version(), f.embeddingChange("candidate"), "forged"));
        assertThat(f.root()).isEqualTo(before);
    }

    @Test void secretCOWPreserveReferenceClearAndLiteralMigration() throws Exception {
        var configured = new EmbeddingConfigRequest(null, null, "literal-secret", null, null, null, null, false);
        var saved = f.globals.updateEmbedding(f.version(), configured, null);
        String reference = saved.value().apiKey();
        assertThat(reference).startsWith("${PRJXP_EMBEDDING_API_KEY_");
        assertThat(Files.readString(f.yaml)).doesNotContain("literal-secret");
        assertThat(f.store.resolve(reference)).isEqualTo("literal-secret");
        String envBefore = Files.readString(f.env);
        f.globals.updateEmbedding(saved.configVersion(),
                new EmbeddingConfigRequest(null, null, "********", null, null, null, null, false), null);
        assertThat(Files.readString(f.env)).isEqualTo(envBefore);
        f.globals.updateEmbedding(f.version(), configured, null);
        assertThat(Files.readString(f.env)).isEqualTo(envBefore);
        f.globals.updateEmbedding(f.version(),
                new EmbeddingConfigRequest(null, null, "replacement", null, null, null, null, false), null);
        assertThat(f.store.resolve(reference)).isEqualTo("literal-secret");
        new EnvFileWriter(f.env).upsert("OTHER_SECRET", "from-ref");
        f.globals.updateEmbedding(f.version(),
                new EmbeddingConfigRequest(null, null, "${OTHER_SECRET}", null, null, null, null, false), null);
        assertThat(f.globals.embedding().value().apiKey()).isEqualTo("${OTHER_SECRET}");
        f.globals.updateEmbedding(f.version(),
                new EmbeddingConfigRequest(null, null, "", null, null, null, null, false), null);
        assertThat(f.globals.embedding().value().apiKey()).isEmpty();
        assertThat(new EnvFileWriter(f.env).read()).containsKey("OTHER_SECRET");
    }

    @Test void legacyLiteralSecretMigratesWithoutChangingEffectiveProof() throws Exception {
        Files.writeString(f.yaml, Files.readString(f.yaml).replace("type: OLLAMA", "type: OLLAMA\n    api-key: existing-literal"));
        var proof = f.embeddingReceipt(f.embeddingChange(null));
        assertThat(f.globals.embedding().value().apiKey()).isEqualTo("********");
        f.globals.updateEmbedding(proof.configVersion(), f.embeddingChange(null), null);
        assertThat(f.globals.embedding().value().apiKey()).startsWith("${");
        assertThat(f.setup.readStatus().embedding().tested()).isTrue();
    }

    @Test void dimensionsResetIsExplicitAndNullPreservesOverride() {
        f.globals.updateEmbedding(f.version(), new EmbeddingConfigRequest(null, null, null, null, 64, null, null, false), null);
        f.globals.updateEmbedding(f.version(), f.embeddingChange(null), null);
        assertThat(f.globals.embedding().value().dimensions()).isEqualTo(64);
        f.globals.updateEmbedding(f.version(), new EmbeddingConfigRequest(null, null, null, null, null, null, null, true), null);
        assertThat(f.globals.embedding().value().dimensions()).isNull();
        assertCode("INVALID_REQUEST", () -> f.globals.updateEmbedding(f.version(),
                new EmbeddingConfigRequest(null, null, null, null, 64, null, null, true), null));
    }

    @Test void sharedSetupSaveThenChatWriteCannotRestoreStaleEmbedding() {
        var request = f.embeddingChange("written-by-setup");
        var proof = f.embeddingReceipt(request);
        var status = f.setup.save(new SetupSaveRequest(proof.configVersion(), "embedding", false,
                proof.testId(), request, null, null, null));
        f.globals.saveChat(status.configVersion(), "coder", f.chatChange(null, "updated-code"), null, null, false, null);
        assertThat(f.globals.embedding().value().modelName()).isEqualTo("written-by-setup");
        assertThat(f.setup.readStatus().embedding().tested()).isTrue();
        assertThat(f.setup.readStatus().completed()).isTrue();
    }

    @Test void staleWriterAndExternalEditsConflictInsteadOfLosingUpdate() throws Exception {
        String loaded = f.version();
        f.globals.updateEmbedding(loaded, f.embeddingChange("first"), null);
        assertCode("CONFIG_CHANGED", () -> f.globals.saveChat(loaded, "coder", f.chatChange(null, "second"), null, null, false, null));
        String newer = f.version();
        Files.writeString(f.yaml, Files.readString(f.yaml) + "\nexternal: kept\n");
        assertCode("CONFIG_CHANGED", () -> f.globals.updateEmbedding(newer, f.embeddingChange("lost"), null));
        assertThat(f.root()).containsEntry("external", "kept");
    }

    @Test void concurrentGlobalsWritersShareStoreLockAndOnlyOneCommits() throws Exception {
        String version = f.version();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (String candidate : List.of("one", "two")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try { release.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    try { f.globals.updateEmbedding(version, f.embeddingChange(candidate), null); results.add("saved"); }
                    catch (SetupException error) { results.add(error.code()); }
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(results).containsExactlyInAnyOrder("saved", "CONFIG_CHANGED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void persistedProofAndCompletionSurviveRestartWhileActivationStateResets() {
        var candidate = f.embeddingChange("next-boot");
        var receipt = f.embeddingReceipt(candidate);
        f.globals.updateEmbedding(receipt.configVersion(), candidate, receipt.testId());
        assertThat(f.setup.readStatus().restartRequired()).isTrue();
        var restartedStore = new ConfigurationStore(f.yaml, f.env, new org.springframework.mock.env.MockEnvironment(), java.time.Clock.systemUTC());
        var supplier = org.mockito.Mockito.mock(de.spraener.prjxp.common.chat.ChatModelSupplier.class);
        org.mockito.Mockito.when(supplier.canProvide(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var restarted = new SetupPersistenceService(restartedStore, new SetupTestEvidenceService(), List.of(supplier));
        assertThat(restarted.readStatus().completed()).isTrue();
        assertThat(restarted.readStatus().embedding().tested()).isTrue();
        assertThat(restarted.readStatus().restartRequired()).isFalse();
        assertThat(restarted.readConfiguration().embedding().modelName()).isEqualTo("next-boot");
    }

    @Test void defaultChatProofPreservesUnchangedInvalidatesChangedAndIgnoresOtherStereotypes() {
        var proof = f.chatReceipt(f.chatChange("default", null));
        assertThat(f.setup.readStatus().chat().tested()).isTrue();
        f.globals.saveChat(proof.configVersion(), "default", f.chatChange(null, null), null, null, false, null);
        var savedProof = tests().get("chat");
        f.globals.saveChat(f.version(), "coder", f.chatChange(null, "different"), null, null, false, null);
        assertThat(tests().get("chat")).isEqualTo(savedProof);
        f.globals.saveChat(f.version(), "default", f.chatChange(null, "different"), null, null, false, null);
        assertThat(f.setup.readStatus().chat().tested()).isFalse();
        assertThat(f.setup.readStatus().completed()).isTrue();
        assertThat(chat("coder")).containsEntry("prompt", "code instructions");
        assertThat(chat("coder")).containsKeys("trust-all-certs", "custom-headers", "custom-query-params", "body-properties", "supports-tools");
    }

    @Test void defaultChatReceiptAndPromptArgsAreBoundToEffectiveCandidate() {
        var request = f.chatChange("default", "tested");
        var proof = f.chatReceipt(request);
        f.globals.saveChat(proof.configVersion(), "default", request, null, null, false, proof.testId());
        assertThat(f.setup.readStatus().chat().tested()).isTrue();
        assertCode("TEST_RECEIPT_INVALID", () -> f.globals.saveChat(f.version(), "default", request,
                "different prompt", null, false, proof.testId()));
        f.globals.saveChat(f.version(), "default", f.chatChange(null, null), "", Map.of("keep", "changed"), false, null);
        assertThat(chat("default")).doesNotContainKey("prompt");
        var effectiveArgs = SetupData.section(new SetupConfigurationNormalizer(f.store, List.of()).chat(f.root()), "args");
        assertThat(effectiveArgs).containsEntry("keep", "changed").containsKey("trust-all-certs");
        assertThat(f.setup.readStatus().chat().tested()).isFalse();
    }

    @Test void chatCreateUpdateRenameDeleteAndSecretMaskingPreserveUnrelatedModelProperties() throws Exception {
        var request = new ChatModelRequest("new", "ollama", "model", "chat-secret", "http://localhost:11434", 30, 0.4);
        GlobalsConfigurationService.Versioned<de.spraener.prjxp.common.config.PrjXPChatModelReference> saved;
        try {
            saved = f.globals.saveChat(f.version(), "new", request, "prompt", Map.of("api-key", "arg-secret", "keep", "value"), true, null);
        } catch (SetupException error) {
            if (!"CONFIG_WRITE_FAILED".equals(error.code())) throw error;
            throw new AssertionError("Copy-on-write commit failed at " + f.commitStage
                    + (f.yamlWriteFailure == null ? " (environment upsert; Store discarded IOException)" : ""),
                    f.yamlWriteFailure == null ? error : f.yamlWriteFailure);
        }
        assertThat(saved.value().getApiKey()).startsWith("${PRJXP_CHAT_API_KEY_");
        assertThat(saved.value().getArgs().get("api-key")).startsWith("${PRJXP_CHAT_API_KEY_");
        assertThat(Files.readString(f.yaml)).doesNotContain("chat-secret", "arg-secret");
        var updated = f.globals.saveChat(saved.configVersion(), "new", f.chatChange("renamed", "other"),
                null, Map.of("api-key", "********"), false, null);
        assertThat(updated.value().getStereoType()).isEqualTo("renamed");
        assertThat(updated.value().getPrompt()).isEqualTo("prompt");
        f.globals.deleteChat(updated.configVersion(), "renamed");
        assertThat(f.globals.chats().value()).hasSize(2);
        f.globals.deleteChat(f.version(), "default");
        assertThat(tests()).doesNotContainKey("chat");
    }

    @Test void arbitraryUpdatedArgumentSecretsUseCowWithoutChangingConnectionFlags() throws Exception {
        f.globals.saveChat(f.version(), "coder", f.chatChange(null, null), null,
                Map.of("opaque-argument", "unsafe-literal", "trust-all-certs", "true"), false, null);
        assertThat(Files.readString(f.yaml)).doesNotContain("unsafe-literal");
        var raw = SetupData.section(chat("coder"), "args");
        assertThat(raw.get("opaque-argument").toString()).startsWith("${PRJXP_CHAT_API_KEY_");
        assertThat(raw).containsEntry("trust-all-certs", "true");
        var candidate = f.globals.chatCandidate(f.version(), "coder", f.chatChange(null, null), null, null);
        assertThat(candidate.value().getArgs()).containsEntry("opaque-argument", "unsafe-literal")
                .containsEntry("trust-all-certs", "true");
        String envBefore = Files.readString(f.env);
        f.globals.saveChat(f.version(), "coder", f.chatChange(null, null), null,
                Map.of("opaque-argument", "********"), false, null);
        assertThat(Files.readString(f.env)).isEqualTo(envBefore);
    }

    @Test void renamingDefaultInvalidatesProofButMarkerStays() {
        f.chatReceipt(f.chatChange("default", null));
        f.globals.saveChat(f.version(), "default", f.chatChange("renamed", null), null, null, false, null);
        assertThat(tests()).doesNotContainKey("chat");
        assertThat(f.setup.readStatus().completed()).isTrue();
    }

    @Test void chatDuplicateMissingAndReceiptForNondefaultAreRejectedWithoutMutation() {
        var before = f.root();
        assertCode("INVALID_CONFIG", () -> f.globals.saveChat(f.version(), "default", f.chatChange("default", "a"), null, null, true, null));
        assertCode("INVALID_CONFIG", () -> f.globals.saveChat(f.version(), "missing", f.chatChange(null, "a"), null, null, false, null));
        assertCode("INVALID_CONFIG", () -> f.globals.saveChat(f.version(), "coder", f.chatChange("default", "a"), null, null, false, null));
        assertCode("INVALID_CONFIG", () -> f.globals.deleteChat(f.version(), "missing"));
        assertCode("TEST_RECEIPT_INVALID", () -> f.globals.saveChat(f.version(), "coder", f.chatChange(null, null), null, null, false, "receipt"));
        assertThat(f.root()).isEqualTo(before);
    }

    @Test void listSecretsAndFreshChatCandidatesResolveMaskAndEnvWithoutChangingStoredValues() throws Exception {
        Files.writeString(f.yaml, Files.readString(f.yaml).replace("modelName: chat", "modelName: chat\n    apiKey: raw-secret"));
        new EnvFileWriter(f.env).upsert("HEADER", "header-secret");
        assertThat(f.globals.chats().value().getFirst().getApiKey()).isEqualTo("********");
        assertThat(f.globals.resolveListSecret("********", "default", false)).isEqualTo("raw-secret");
        assertThat(f.globals.resolveListSecret("${HEADER}", "default", false)).isEqualTo("header-secret");
        var before = f.root();
        var candidate = f.globals.chatCandidate(f.version(), "default", f.chatChange(null, "draft"),
                "test prompt", Map.of("Authorization", "${HEADER}"));
        assertThat(candidate.value().getModelName()).isEqualTo("draft");
        assertThat(candidate.value().getApiKey()).isEqualTo("raw-secret");
        assertThat(candidate.value().getPrompt()).isEqualTo("test prompt");
        assertThat(candidate.value().getArgs()).containsEntry("Authorization", "header-secret").containsEntry("keep", "original");
        assertThat(f.root()).isEqualTo(before);
        assertThat(f.globals.chatCandidate(f.version(), "new", new ChatModelRequest("new", "ollama", "draft",
                null, "http://localhost:11434", null, null), null, null).value().getStereoType()).isEqualTo("new");
    }

    @Test void unresolvedMaskAndMalformedDataCannotWrite() throws Exception {
        assertCode("MISSING_SECRET", () -> f.globals.resolveListSecret("********", "missing", false));
        Files.writeString(f.yaml, "prjxp:\n  embedding: wrong\n");
        assertCode("INVALID_CONFIG", () -> f.globals.embedding());
    }

    @Test void readFailureNeverFallsBackToStartupData() throws Exception {
        Files.delete(f.yaml);
        Files.createDirectory(f.yaml);
        assertCode("CONFIG_READ_FAILED", () -> f.globals.embedding());
    }

    @Test void nonemptyIndexBlocksUntestedChangedCandidateAndPreservesFiles() throws Exception {
        Path index = directory.resolve("index");
        try (var fs = FSDirectory.open(index); var writer = new IndexWriter(fs, new IndexWriterConfig())) {
            Document document = new Document();
            document.add(new StoredField("_id", "id"));
            document.add(new StoredField("content", "content"));
            document.add(new KnnFloatVectorField("vector", new float[]{1, 0}));
            writer.addDocument(document);
        }
        var before = f.root();
        assertCode("INDEX_REBUILD_REQUIRED", () -> f.globals.updateEmbedding(f.version(), f.embeddingChange("incompatible"), null));
        assertThat(f.root()).isEqualTo(before);
    }

    @Test void yamlCommitFailureDoesNotReportSuccessOrChangeOldSecretReference() throws Exception {
        var existing = f.globals.updateEmbedding(f.version(),
                new EmbeddingConfigRequest(null, null, "old-secret", null, null, null, null, false), null);
        String oldReference = existing.value().apiKey();
        var failing = org.mockito.Mockito.spy(f.store);
        org.mockito.Mockito.doThrow(new java.io.IOException("synthetic-private-write-details"))
                .when(failing).writeYaml(org.mockito.ArgumentMatchers.anyString());
        var service = new GlobalsConfigurationService(failing, f.evidence, List.of());
        var before = Files.readString(f.yaml);
        assertThatThrownBy(() -> service.updateEmbedding(failing.readSnapshot().configVersion(),
                new EmbeddingConfigRequest(null, null, "new-secret", "changed", null, null, null, false), null))
                .isInstanceOf(SetupException.class).hasMessage("Cannot commit setup configuration")
                .satisfies(error -> assertThat(((SetupException) error).code()).isEqualTo("CONFIG_WRITE_FAILED"));
        org.mockito.Mockito.verify(failing).writeYaml(org.mockito.ArgumentMatchers.anyString());
        assertThat(Files.readString(f.yaml)).isEqualTo(before);
        assertThat(Files.readString(f.env)).contains("new-secret");
        assertThat(f.store.resolve(oldReference)).isEqualTo("old-secret");
    }

    @ParameterizedTest @ValueSource(strings = {"embedding", "chat"})
    void changedSecretInvalidatesPersistedProof(String step) throws Exception {
        if ("embedding".equals(step)) {
            f.embeddingReceipt(f.embeddingChange(null));
            f.globals.updateEmbedding(f.version(), new EmbeddingConfigRequest(null, null, "secret", null, null, null, null, false), null);
            assertThat(f.setup.readStatus().embedding().tested()).isFalse();
        } else {
            f.chatReceipt(f.chatChange("default", null));
            f.globals.saveChat(f.version(), "default", new ChatModelRequest(null, null, null, "secret", null, null, null), null, null, false, null);
            assertThat(f.setup.readStatus().chat().tested()).isFalse();
        }
    }

    private Map<String, Object> prjxp() { return SetupData.section(f.root(), "prjxp"); }
    private Map<String, Object> embedding() { return SetupData.section(prjxp(), "embedding"); }
    private Map<String, Object> tests() { return SetupData.section(SetupData.section(prjxp(), "setup-state"), "tests"); }
    private Map<String, Object> chat(String name) {
        return ((List<?>) SetupData.get(prjxp(), "chat-models")).stream().map(SetupData::map)
                .filter(model -> name.equals(SetupData.get(model, "stereo-type"))).findFirst().orElseThrow();
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(SetupException.class)
                .satisfies(error -> assertThat(((SetupException) error).code()).isEqualTo(code));
    }
}
