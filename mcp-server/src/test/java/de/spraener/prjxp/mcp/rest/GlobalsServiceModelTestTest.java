package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.config.EnvFileWriter;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.embedding.EmbeddingModelFactory;
import de.spraener.prjxp.common.model.ModelLister;
import de.spraener.prjxp.mcp.setup.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalsServiceModelTestTest {
    @TempDir Path directory;
    GlobalsTestFixture f;
    GlobalsService service;
    EmbeddingModelFactory factory;
    KIChatModelProvider chats;
    ModelLister lister;
    EmbeddingModel model;

    @BeforeEach void start() throws Exception {
        f = new GlobalsTestFixture(directory);
        factory = mock(EmbeddingModelFactory.class);
        chats = mock(KIChatModelProvider.class);
        lister = mock(ModelLister.class);
        model = mock(EmbeddingModel.class);
        when(factory.create(any())).thenReturn(model);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1, 0, 1}))));
        service = new GlobalsService(f.globals, new SetupModelTestService(f.setup, factory, chats, mock(ObjectProvider.class)), chats, lister);
    }

    @Test void newEmbeddingTestUsesSharedFactorySettingsAndPersistsMatchingObservation() {
        var response = service.testEmbeddingConfig(new SetupTestRequest<>(f.version(), f.embeddingChange(null)));
        assertThat(response.success()).isTrue();
        assertThat(response.dimensions()).isEqualTo(3);
        assertThat(response.testId()).isNotBlank();
        assertThat(response.configVersion()).isEqualTo(f.version());
        assertThat(f.setup.readStatus().embedding().tested()).isTrue();
        var candidate = ArgumentCaptor.forClass(PrjXPConfig.EmbeddingConfig.class);
        verify(factory).create(candidate.capture());
        assertThat(candidate.getValue().getApiBaseURL()).isEqualTo("http://localhost:11434");
        assertThat(candidate.getValue().getMaxSegmentsPerBatch()).isEqualTo(32);
        assertThat(candidate.getValue().isTrustAllCerts()).isTrue();
        assertThat(candidate.getValue().getCustomHeaders()).containsEntry("X-Keep", "value");
        assertThat(candidate.getValue().getCustomQueryParams()).containsKey("keep");
    }

    @Test void legacyTestOnlyReturnsModelTestResultAndAlsoUpdatesSharedProof() {
        var request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType("ollama");
        var response = service.testEmbeddingModel(f.version(), request);
        assertThat(response.value().isSuccess()).isTrue();
        assertThat(response.value().getDimensions()).isEqualTo(3);
        assertThat(f.setup.readStatus().embedding().tested()).isTrue();
        assertThat(response.value().getClass()).isEqualTo(ModelTestResult.class);
    }

    @Test void defaultChatUsesFreshSharedTestAndPreservesPromptArgs() {
        KIChat chat = mock(KIChat.class);
        when(chats.createFreshKIChat(any())).thenReturn(chat);
        when(chat.chat("Say hello")).thenReturn("hello");
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("default");
        var response = service.testChatModel(f.version(), request);
        assertThat(response.value().isSuccess()).isTrue();
        assertThat(f.setup.readStatus().chat().tested()).isTrue();
        var captured = ArgumentCaptor.forClass(de.spraener.prjxp.common.config.PrjXPChatModelReference.class);
        verify(chats).createFreshKIChat(captured.capture());
        assertThat(captured.getValue().getPrompt()).isEqualTo("instructions");
        assertThat(captured.getValue().getArgs()).containsEntry("keep", "original");
        verify(chats, never()).clearCache();
    }

    @Test void defaultLegacyDraftPromptAndPartialSecretArgsReachFreshProviderWithoutWritingDraft() throws Exception {
        new EnvFileWriter(f.env).upsert("DRAFT_ARGUMENT", "argument-secret");
        var before = f.root();
        KIChat chat = mock(KIChat.class);
        when(chats.createFreshKIChat(any())).thenReturn(chat);
        when(chat.chat("Say hello")).thenReturn("draft hello");
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("default");
        request.setPrompt("draft instructions");
        request.setArgs(java.util.Map.of("Authorization", "${DRAFT_ARGUMENT}", "keep", "********"));
        var response = service.testChatModel(f.version(), request);
        assertThat(response.value().isSuccess()).isTrue();
        assertThat(response.configVersion()).isEqualTo(f.version());
        var captured = ArgumentCaptor.forClass(de.spraener.prjxp.common.config.PrjXPChatModelReference.class);
        verify(chats).createFreshKIChat(captured.capture());
        assertThat(captured.getValue().getPrompt()).isEqualTo("draft instructions");
        assertThat(captured.getValue().getArgs()).containsEntry("Authorization", "argument-secret")
                .containsEntry("keep", "original").containsEntry("trust-all-certs", "true");
        assertThat(f.root()).isEqualTo(before);
        assertThat(f.setup.readStatus().chat().tested()).isFalse();
        assertThat(service.getChatModels().value()).anySatisfy(model -> {
            assertThat(model.getStereoType()).isEqualTo("coder");
            assertThat(model.getPrompt()).isEqualTo("code instructions");
        });
    }

    @Test void defaultLegacyTestForwardsRawPromptAndArgsToSharedOverload() {
        var tests = mock(SetupModelTestService.class);
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setPrompt("submitted prompt");
        request.setArgs(java.util.Map.of("api-key", "********"));
        String version = f.version();
        when(tests.testChat(any(), eq("submitted prompt"), eq(request.getArgs())))
                .thenReturn(new SetupTestResult(true, "ok", 1, "hello", null, "receipt", "post-test-version"));
        var globals = new GlobalsService(f.globals, tests, chats, lister);
        var result = globals.testChatModel(version, request);
        assertThat(result.configVersion()).isEqualTo("post-test-version");
        assertThat(result.value().getResponse()).isEqualTo("hello");
        var captured = ArgumentCaptor.forClass(SetupTestRequest.class);
        verify(tests).testChat(captured.capture(), eq("submitted prompt"), eq(request.getArgs()));
        assertThat(captured.getValue().configVersion()).isEqualTo(version);
    }

    @Test void failedSharedTestRedactsProviderMessageAndInvalidatesMatchingProof() {
        service.testEmbeddingConfig(new SetupTestRequest<>(f.version(), f.embeddingChange(null)));
        when(factory.create(any())).thenThrow(new HttpException(401, "Authorization secret body"));
        var response = service.testEmbeddingModel(f.version(), new GlobalsController.PrjXPEmbeddingModelRequest());
        assertThat(response.value().isSuccess()).isFalse();
        assertThat(response.value().getMessage()).doesNotContain("Authorization", "secret", "body");
        assertThat(f.setup.readStatus().embedding().tested()).isFalse();
    }

    @Test void nonDefaultChatTestIsFreshAndDoesNotInvalidateDefaultProof() {
        f.chatReceipt(f.chatChange("default", null));
        KIChat chat = mock(KIChat.class);
        when(chats.createFreshKIChat(any())).thenReturn(chat);
        when(chat.chat("Say hello")).thenReturn("hello");
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("coder");
        request.setPrompt("draft prompt");
        var response = service.testChatModel(f.version(), request);
        assertThat(response.value().isSuccess()).isTrue();
        assertThat(f.setup.readStatus().chat().tested()).isTrue();
        when(chat.chat("Say hello")).thenReturn(" ");
        assertThat(service.testChatModel(f.version(), request).value().isSuccess()).isFalse();
        when(chats.createFreshKIChat(any())).thenThrow(new HttpException(500, "secret provider body"));
        assertThat(service.testChatModel(f.version(), request).value().getMessage()).doesNotContain("secret", "body");
    }

    @Test void modelListRetainsRawParameterContractButResolvesCurrentMaskAndEnv() throws Exception {
        new EnvFileWriter(f.env).upsert("LIST_SECRET", "resolved-key");
        new EnvFileWriter(f.env).upsert("LIST_URL", "https://models.test");
        f.globals.updateEmbedding(f.version(), new EmbeddingConfigRequest(null, null, "${LIST_SECRET}", null, null, null, null, false), null);
        when(lister.listModels("openai", "https://models.test", "resolved-key", 30)).thenReturn(List.of("model"));
        assertThat(service.listEmbeddingModels("openai", "${LIST_URL}", "********")).containsExactly("model");
        assertThat(service.listChatModels("ollama", "http://host", "", "coder")).isEmpty();
        verify(lister).listModels("ollama", "http://host", "", 30);
        Files.writeString(f.env, Files.readString(f.env).replace("resolved-key", "fresh-key"));
        service.listEmbeddingModels("openai", "${LIST_URL}", "********");
        verify(lister).listModels("openai", "https://models.test", "fresh-key", 30);
    }

    @Test void unexpectedInternalErrorIsNotDisguisedAsProviderFailure() {
        when(factory.create(any())).thenThrow(new IllegalStateException("internal"));
        assertThatThrownBy(() -> service.testEmbeddingConfig(new SetupTestRequest<>(f.version(), f.embeddingChange(null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void nondefaultProviderEchoesAreRedactedAndInterruptedTransportRestoresFlag() {
        KIChat chat = mock(KIChat.class);
        when(chats.createFreshKIChat(any())).thenReturn(chat);
        when(chat.chat("Say hello")).thenReturn("echo secret and argument-secret");
        var request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("coder");
        request.setApiKey("secret");
        request.setArgs(java.util.Map.of("Authorization", "argument-secret"));
        assertThat(service.testChatModel(f.version(), request).value().getResponse())
                .doesNotContain("secret").contains("[REDACTED]");
        var interrupted = new HttpException(500, "private");
        interrupted.initCause(new InterruptedException());
        doThrow(interrupted).when(chat).chat("Say hello");
        try {
            assertThat(service.testChatModel(f.version(), request).value().getMessage()).contains("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
