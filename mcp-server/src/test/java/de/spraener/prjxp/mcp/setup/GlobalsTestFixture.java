package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public final class GlobalsTestFixture {
    public final ConfigurationStore store;
    public final SetupTestEvidenceService evidence = new SetupTestEvidenceService();
    public final SetupPersistenceService setup;
    public final GlobalsConfigurationService globals;
    public final Path yaml;
    public final Path env;
    public volatile String commitStage = "before-yaml";
    public volatile java.io.IOException yamlWriteFailure;

    public GlobalsTestFixture(Path directory) throws Exception {
        this(directory, """
                prjxp:
                  setup-completed: true
                  embedding:
                    type: OLLAMA
                    apiBaseURL: http://localhost:11434
                    modelName: original
                    trust-all-certs: true
                    custom-headers: {X-Keep: value}
                    custom-query-params: {keep: yes}
                  embedding-model: {server-type: gemini, model-name: untouched}
                  chat-models:
                    - stereoType: default
                      serverType: ollama
                      modelName: chat
                      providerUrl: http://localhost:11434
                      prompt: instructions
                      args: {keep: original, trust-all-certs: true}
                    - stereo-type: coder
                      server-type: ollama
                      model-name: code
                      provider-url: http://localhost:11434
                      prompt: code instructions
                      args: {keep: coder}
                      supports-tools: false
                      body-properties: {enable_thinking: false}
                      trust-all-certs: false
                      custom-headers: {X-Raw: preserved}
                      custom-query-params: {raw: kept}
                  setup-state:
                    completion-mode: DEFERRED
                    saved-steps: {github: SKIPPED}
                    tests: {github: {keep: unchanged}}
                foreign: {keep: [1, 2]}
                """);
    }

    public GlobalsTestFixture(Path directory, String text) throws Exception {
        yaml = directory.resolve("application.yaml");
        env = directory.resolve(".env");
        Map<String, Object> root = SetupData.map(new Yaml().load(text));
        Map<String, Object> prjxp = SetupData.section(root, "prjxp");
        Map<String, Object> lucene = SetupData.section(prjxp, "embedding-store-lucene");
        lucene.putIfAbsent("index-path", directory.resolve("index").toString());
        SetupData.put(prjxp, "embedding-store-lucene", lucene);
        SetupData.put(root, "prjxp", prjxp);
        Files.writeString(yaml, new Yaml().dump(root));
        store = new ConfigurationStore(yaml, env, new MockEnvironment(), Clock.systemUTC()) {
            @Override protected void writeYaml(String text) throws java.io.IOException {
                commitStage = "yaml-atomic-write";
                try {
                    super.writeYaml(text);
                    commitStage = "yaml-committed";
                } catch (java.io.IOException error) {
                    yamlWriteFailure = error;
                    throw error;
                }
            }
        };
        ChatModelSupplier supplier = mock(ChatModelSupplier.class);
        when(supplier.canProvide(any())).thenReturn(true);
        List<ChatModelSupplier> suppliers = List.of(supplier);
        setup = new SetupPersistenceService(store, evidence, suppliers);
        globals = new GlobalsConfigurationService(store, evidence, suppliers);
    }

    public String version() { return store.readSnapshot().configVersion(); }
    public Map<String, Object> root() { return store.readSnapshot().root(); }
    public EmbeddingConfigRequest embeddingChange(String model) {
        return new EmbeddingConfigRequest(null, null, null, model, null, null, null, false);
    }
    public ChatModelRequest chatChange(String name, String model) {
        return new ChatModelRequest(name, null, model, null, null, null, null);
    }
    public SetupTestResult embeddingReceipt(EmbeddingConfigRequest request) {
        var prepared = setup.prepareEmbeddingTest(new SetupTestRequest<>(version(), request));
        return setup.recordTest(prepared, new SetupTestResult(true, "ok", 1, null, 64, null, null));
    }
    public SetupTestResult chatReceipt(ChatModelRequest request) {
        var prepared = setup.prepareChatTest(new SetupTestRequest<>(version(), request));
        return setup.recordTest(prepared, new SetupTestResult(true, "ok", 1, "hello", null, null, null));
    }
}
