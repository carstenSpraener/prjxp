package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GlobalsServiceTest {

    @Mock
    private KIChatModelProvider kiChatModelProvider;

    private PrjXPConfig prjXPConfig;
    private GlobalsService globalsService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        prjXPConfig = new PrjXPConfig();
        prjXPConfig.setChatModels(new ArrayList<>());

        // Create a dummy application.yaml so the service can find it
        Path yamlPath = tempDir.resolve("application.yaml");
        Files.writeString(yamlPath, "prjxp:\n  chat-models: []\n");

        // Temporarily set user.dir to tempDir so the service finds our test file
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());

        try {
            globalsService = new GlobalsService(prjXPConfig, kiChatModelProvider);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void getChatModels_emptyByDefault() {
        List<PrjXPChatModelReference> models = globalsService.getChatModels();
        assertThat(models).isEmpty();
    }

    @Test
    void getChatModels_returnsConfiguredModels() {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("default");
        model.setServerType("ollama");
        model.setModelName("llama3");
        model.setProviderUrl("http://localhost:11434");
        prjXPConfig.getChatModels().add(model);

        List<PrjXPChatModelReference> models = globalsService.getChatModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).getStereoType()).isEqualTo("default");
    }

    @Test
    void createChatModel_addsNewModel() {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("coder");
        model.setServerType("lm-studio");
        model.setModelName("gemma-4-31b");
        model.setProviderUrl("http://localhost:1234");

        PrjXPChatModelReference created = globalsService.createChatModel(model);
        assertThat(created.getStereoType()).isEqualTo("coder");
        assertThat(prjXPConfig.getChatModels()).hasSize(1);
        verify(kiChatModelProvider).clearCache();
    }

    @Test
    void createChatModel_throwsOnDuplicateStereoType() {
        PrjXPChatModelReference existing = new PrjXPChatModelReference();
        existing.setStereoType("default");
        prjXPConfig.getChatModels().add(existing);

        PrjXPChatModelReference duplicate = new PrjXPChatModelReference();
        duplicate.setStereoType("default");

        assertThatThrownBy(() -> globalsService.createChatModel(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateChatModel_updatesExistingModel() {
        PrjXPChatModelReference existing = new PrjXPChatModelReference();
        existing.setStereoType("default");
        existing.setServerType("ollama");
        existing.setModelName("llama3");
        prjXPConfig.getChatModels().add(existing);

        GlobalsService.PrjXPChatModelRequest request = new GlobalsService.PrjXPChatModelRequest();
        request.setModelName("llama3.1");
        request.setTemperature(0.8);

        PrjXPChatModelReference updated = globalsService.updateChatModel("default", request);
        assertThat(updated.getModelName()).isEqualTo("llama3.1");
        assertThat(updated.getTemperature()).isEqualTo(0.8);
        // Original serverType should be preserved
        assertThat(updated.getServerType()).isEqualTo("ollama");
        verify(kiChatModelProvider).clearCache();
    }

    @Test
    void updateChatModel_throwsWhenNotFound() {
        GlobalsService.PrjXPChatModelRequest request = new GlobalsService.PrjXPChatModelRequest();
        request.setModelName("new-model");

        assertThatThrownBy(() -> globalsService.updateChatModel("nonexistent", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void deleteChatModel_removesExistingModel() {
        PrjXPChatModelReference existing = new PrjXPChatModelReference();
        existing.setStereoType("default");
        prjXPConfig.getChatModels().add(existing);

        globalsService.deleteChatModel("default");
        assertThat(prjXPConfig.getChatModels()).isEmpty();
        verify(kiChatModelProvider).clearCache();
    }

    @Test
    void deleteChatModel_throwsWhenNotFound() {
        assertThatThrownBy(() -> globalsService.deleteChatModel("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getServerTypes_returnsAllServerTypeStrings() {
        List<String> types = globalsService.getServerTypes();
        assertThat(types).contains("ollama", "openapi", "lm-studio", "gemini", "custom");
    }

    @Test
    void createChatModel_withArgs() {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("custom-model");
        model.setServerType("custom");
        model.setModelName("my-custom-model");
        model.setProviderUrl("http://custom.example.com");
        model.setArgs(Map.of("header", "value"));

        PrjXPChatModelReference created = globalsService.createChatModel(model);
        assertThat(created.getArgs()).containsEntry("header", "value");
    }

    @Test
    void persistToYaml_writesFile() {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test");
        model.setServerType("ollama");
        model.setModelName("llama3");
        model.setProviderUrl("http://localhost:11434");

        globalsService.createChatModel(model);

        // Verify the model was added to config
        assertThat(prjXPConfig.getChatModels()).hasSize(1);
    }

    @Test
    void getEmbeddingModel_returnsDefaultWhenNotConfigured() {
        // embeddingModel is null by default in PrjXPConfig
        PrjXPEmbeddingModelReference result = globalsService.getEmbeddingModel();

        assertThat(result.getServerType()).isEqualTo("ollama");
        assertThat(result.getTimeoutSecs()).isEqualTo(60);
        assertThat(result.getProviderUrl()).isNull();
        assertThat(result.getModelName()).isNull();
        assertThat(result.getApiKey()).isNull();
    }

    @Test
    void getEmbeddingModel_returnsCopyOfConfiguredModel() {
        PrjXPEmbeddingModelReference configured = new PrjXPEmbeddingModelReference();
        configured.setServerType("openai");
        configured.setProviderUrl("https://api.openai.com/v1");
        configured.setModelName("text-embedding-3-small");
        configured.setApiKey("sk-test-key");
        configured.setTimeoutSecs(30);
        prjXPConfig.setEmbeddingModel(configured);

        PrjXPEmbeddingModelReference result = globalsService.getEmbeddingModel();

        assertThat(result.getServerType()).isEqualTo("openai");
        assertThat(result.getProviderUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(result.getModelName()).isEqualTo("text-embedding-3-small");
        assertThat(result.getApiKey()).isEqualTo("sk-test-key");
        assertThat(result.getTimeoutSecs()).isEqualTo(30);

        // Verify it's a copy, not the same instance
        assertThat(result).isNotSameAs(configured);
    }

    @Test
    void updateEmbeddingModel_savesAndPersists() {
        GlobalsService.PrjXPEmbeddingModelRequest request = new GlobalsService.PrjXPEmbeddingModelRequest();
        request.setServerType("gemini");
        request.setProviderUrl("https://generativelanguage.googleapis.com/v1beta");
        request.setModelName("embedding-gecko-001");
        request.setApiKey("gemini-key-123");
        request.setTimeoutSecs(45);

        PrjXPEmbeddingModelReference result = globalsService.updateEmbeddingModel(request);

        assertThat(result.getServerType()).isEqualTo("gemini");
        assertThat(result.getProviderUrl()).isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(result.getModelName()).isEqualTo("embedding-gecko-001");
        assertThat(result.getApiKey()).isEqualTo("gemini-key-123");
        assertThat(result.getTimeoutSecs()).isEqualTo(45);

        // Verify it's stored in the config
        assertThat(prjXPConfig.getEmbeddingModel()).isNotNull();
        assertThat(prjXPConfig.getEmbeddingModel().getServerType()).isEqualTo("gemini");
    }

    @Test
    void updateEmbeddingModel_partialUpdate() {
        // Pre-configure a model
        PrjXPEmbeddingModelReference existing = new PrjXPEmbeddingModelReference();
        existing.setServerType("ollama");
        existing.setModelName("mxbai-embed-large");
        prjXPConfig.setEmbeddingModel(existing);

        // Partial update with only modelName
        GlobalsService.PrjXPEmbeddingModelRequest request = new GlobalsService.PrjXPEmbeddingModelRequest();
        request.setModelName("nomic-embed-text");

        PrjXPEmbeddingModelReference result = globalsService.updateEmbeddingModel(request);

        assertThat(result.getModelName()).isEqualTo("nomic-embed-text");
    }

    @Test
    void updateEmbeddingModel_yamlPersistence() throws java.io.IOException {
        GlobalsService.PrjXPEmbeddingModelRequest request = new GlobalsService.PrjXPEmbeddingModelRequest();
        request.setServerType("lm-studio");
        request.setProviderUrl("http://localhost:1234/v1");
        request.setModelName("nomic-embed-text-v1");

        globalsService.updateEmbeddingModel(request);

        // Verify the config object is updated
        assertThat(prjXPConfig.getEmbeddingModel()).isNotNull();
        assertThat(prjXPConfig.getEmbeddingModel().getServerType()).isEqualTo("lm-studio");
        assertThat(prjXPConfig.getEmbeddingModel().getModelName()).isEqualTo("nomic-embed-text-v1");

        // Verify the YAML file was written
        String yamlContent = Files.readString(tempDir.resolve("application.yaml"));
        assertThat(yamlContent).contains("embedding-model");
        assertThat(yamlContent).contains("lm-studio");
    }

    @Test
    void updateChatModel_setsPrompt() {
        PrjXPChatModelReference existing = new PrjXPChatModelReference();
        existing.setStereoType("default");
        prjXPConfig.getChatModels().add(existing);

        GlobalsService.PrjXPChatModelRequest request = new GlobalsService.PrjXPChatModelRequest();
        request.setPrompt("Be concise.");

        PrjXPChatModelReference updated = globalsService.updateChatModel("default", request);
        assertThat(updated.getPrompt()).isEqualTo("Be concise.");
    }

    @Test
    void updateChatModel_blankPromptClearsIt() {
        PrjXPChatModelReference existing = new PrjXPChatModelReference();
        existing.setStereoType("default");
        existing.setPrompt("Be concise.");
        prjXPConfig.getChatModels().add(existing);

        GlobalsService.PrjXPChatModelRequest request = new GlobalsService.PrjXPChatModelRequest();
        request.setPrompt("   ");

        PrjXPChatModelReference updated = globalsService.updateChatModel("default", request);
        assertThat(updated.getPrompt()).isNull();
    }

    @Test
    void persistToYaml_writesPrompt() throws java.io.IOException {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test");
        model.setPrompt("Always answer in German.");
        globalsService.createChatModel(model);

        String yamlContent = Files.readString(tempDir.resolve("application.yaml"));
        assertThat(yamlContent).contains("prompt");
        assertThat(yamlContent).contains("Always answer in German.");
    }

    @Test
    void persistToYaml_omitsNullPrompt() throws java.io.IOException {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test");
        globalsService.createChatModel(model);

        String yamlContent = Files.readString(tempDir.resolve("application.yaml"));
        assertThat(yamlContent).doesNotContain("prompt");
    }
}
