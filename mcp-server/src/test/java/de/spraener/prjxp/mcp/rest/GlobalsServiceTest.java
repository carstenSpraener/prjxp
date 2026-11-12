package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.model.ModelLister;
import de.spraener.prjxp.mcp.setup.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalsServiceTest {
    @TempDir Path directory;
    GlobalsTestFixture fixture;
    GlobalsService service;
    ModelLister models;
    KIChatModelProvider chats;

    @BeforeEach void start() throws Exception {
        fixture = new GlobalsTestFixture(directory);
        models = mock(ModelLister.class);
        chats = mock(KIChatModelProvider.class);
        service = new GlobalsService(fixture.globals, mock(SetupModelTestService.class), chats, models);
    }

    @Test void embeddingLegacyAdapterReadsCurrentCanonicalConfiguration() {
        assertThat(service.getEmbeddingModel().value().getModelName()).isEqualTo("original");
        assertThat(service.getEmbeddingModel().value().getServerType()).isEqualTo("ollama");
        service.updateEmbeddingConfig(fixture.version(), fixture.embeddingChange("saved"), null);
        assertThat(service.getEmbeddingModel().value().getModelName()).isEqualTo("saved");
        assertThat(service.getEmbeddingConfig().value().modelName()).isEqualTo("saved");
        verifyNoInteractions(chats);
    }

    @ParameterizedTest @ValueSource(strings = {"openai", "openapi", "lm-studio"})
    void legacySupportedOpenAiNamesMapToRuntimeType(String provider) {
        var request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType(provider);
        request.setApiKey("new-secret");
        request.setProviderUrl("https://example.test/v1");
        request.setModelName("text");
        request.setTimeoutSecs(44);
        var saved = service.updateEmbeddingModel(fixture.version(), request, null);
        assertThat(saved.value().getServerType()).isEqualTo("openai");
        assertThat(saved.value().getTimeoutSecs()).isEqualTo(44);
        assertThat(service.getEmbeddingConfig().value().type()).isEqualTo("OPEN_AI");
        assertThat(fixture.root().toString()).doesNotContain("new-secret").contains("untouched");
    }

    @ParameterizedTest @ValueSource(strings = {"gemini", "custom", "unknown"})
    void unsupportedLegacyProviderIsExplicitMigrationError(String provider) {
        var request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType(provider);
        var before = fixture.root();
        assertThatThrownBy(() -> service.updateEmbeddingModel(fixture.version(), request, null))
                .isInstanceOf(SetupException.class).hasMessageContaining("migrate");
        assertThat(fixture.root()).isEqualTo(before);
    }

    @Test void legacyPartialUpdateKeepsUnspecifiedProviderAndSecrets() {
        var request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setModelName("changed");
        assertThat(service.updateEmbeddingModel(fixture.version(), request, null).value().getModelName()).isEqualTo("changed");
        assertThat(service.getEmbeddingConfig().value().timeout()).isEqualTo(20);
        assertThat(service.getServerTypes()).contains("ollama");
    }

    @Test void chatCrudDelegatesWithoutRuntimeMutation() {
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("new");
        request.setServerType("ollama");
        request.setProviderUrl("http://localhost:11434");
        request.setModelName("new");
        request.setTemperature(0.3);
        request.setTimeoutSecs(30);
        request.setPrompt("instructions");
        request.setArgs(java.util.Map.of("keep", "kept"));
        var created = service.createChatModel(fixture.version(), request, null);
        assertThat(created.value().getPrompt()).isEqualTo("instructions");
        request.setModelName("updated");
        var updated = service.updateChatModel(created.configVersion(), "new", request, null);
        assertThat(updated.value().getModelName()).isEqualTo("updated");
        service.deleteChatModel(updated.configVersion(), "new");
        assertThat(service.getChatModels().value()).hasSize(2);
        verifyNoInteractions(chats);
    }
}
