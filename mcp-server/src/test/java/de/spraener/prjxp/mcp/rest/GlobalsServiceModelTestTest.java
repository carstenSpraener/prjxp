package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.embedding.EmbeddingModelProvider;
import de.spraener.prjxp.common.model.ModelLister;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalsServiceModelTestTest {

    @Mock
    private KIChatModelProvider kiChatModelProvider;

    @Mock
    private EmbeddingModelProvider embeddingModelProvider;

    @Mock
    private ModelLister modelLister;

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
            globalsService = new GlobalsService(prjXPConfig, kiChatModelProvider, embeddingModelProvider, modelLister);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void testChatModel_success() {
        KIChat kiChat = mock(KIChat.class);
        when(kiChat.chat("Say hello")).thenReturn("Hello!");
        when(kiChatModelProvider.createFreshKIChat(any())).thenReturn(kiChat);

        GlobalsController.PrjXPChatModelRequest request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("default");
        request.setServerType("ollama");
        request.setModelName("llama3");
        request.setProviderUrl("http://localhost:11434");

        ModelTestResult result = globalsService.testChatModel(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("Hello!");
        assertThat(result.getDimensions()).isNull();
        assertThat(result.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testChatModel_maskedKeyResolvedFromSaved() {
        PrjXPChatModelReference saved = new PrjXPChatModelReference();
        saved.setStereoType("default");
        saved.setApiKey("sk-real");
        prjXPConfig.getChatModels().add(saved);

        KIChat kiChat = mock(KIChat.class);
        when(kiChat.chat("Say hello")).thenReturn("Hello!");
        when(kiChatModelProvider.createFreshKIChat(any())).thenReturn(kiChat);

        GlobalsController.PrjXPChatModelRequest request = new GlobalsController.PrjXPChatModelRequest();
        request.setStereoType("default");
        request.setApiKey("********");

        globalsService.testChatModel(request);

        ArgumentCaptor<PrjXPChatModelReference> captor = ArgumentCaptor.forClass(PrjXPChatModelReference.class);
        verify(kiChatModelProvider).createFreshKIChat(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("sk-real");
    }

    @Test
    void testChatModel_failureReturnsFailure() {
        when(kiChatModelProvider.createFreshKIChat(any())).thenThrow(new RuntimeException("boom"));

        GlobalsController.PrjXPChatModelRequest request = new GlobalsController.PrjXPChatModelRequest();
        request.setServerType("ollama");
        request.setModelName("llama3");
        request.setProviderUrl("http://localhost:11434");

        ModelTestResult result = globalsService.testChatModel(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Chat test failed");
    }

    @Test
    void testEmbeddingModel_success() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("test")).thenReturn(new Response<>(new Embedding(new float[]{1, 2, 3})));
        when(embeddingModelProvider.createEmbeddingModel(any())).thenReturn(model);

        GlobalsController.PrjXPEmbeddingModelRequest request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType("ollama");
        request.setProviderUrl("http://localhost:11434");
        request.setModelName("mxbai-embed-large");

        ModelTestResult result = globalsService.testEmbeddingModel(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDimensions()).isEqualTo(3);
        assertThat(result.getResponse()).isNull();
    }

    @Test
    void testEmbeddingModel_maskedKeyResolvedFromSaved() {
        PrjXPEmbeddingModelReference saved = new PrjXPEmbeddingModelReference();
        saved.setApiKey("sk-emb");
        prjXPConfig.setEmbeddingModel(saved);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("test")).thenReturn(new Response<>(new Embedding(new float[]{1, 2, 3})));
        when(embeddingModelProvider.createEmbeddingModel(any())).thenReturn(model);

        GlobalsController.PrjXPEmbeddingModelRequest request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType("openai");
        request.setApiKey("********");

        globalsService.testEmbeddingModel(request);

        ArgumentCaptor<PrjXPEmbeddingModelReference> captor = ArgumentCaptor.forClass(PrjXPEmbeddingModelReference.class);
        verify(embeddingModelProvider).createEmbeddingModel(captor.capture());
        assertThat(captor.getValue().getApiKey()).isEqualTo("sk-emb");
    }

    @Test
    void testEmbeddingModel_failureReturnsFailure() {
        when(embeddingModelProvider.createEmbeddingModel(any())).thenThrow(new RuntimeException("no provider"));

        GlobalsController.PrjXPEmbeddingModelRequest request = new GlobalsController.PrjXPEmbeddingModelRequest();
        request.setServerType("custom");

        ModelTestResult result = globalsService.testEmbeddingModel(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Embedding test failed");
    }

    @Test
    void listChatModels_delegatesToLister() {
        when(modelLister.listModels("ollama", "http://x", null, 30)).thenReturn(List.of("a", "b"));

        List<String> names = globalsService.listChatModels("ollama", "http://x", null, null);

        assertThat(names).containsExactly("a", "b");
    }

    @Test
    void listChatModels_maskedKeyResolved() {
        PrjXPChatModelReference saved = new PrjXPChatModelReference();
        saved.setStereoType("default");
        saved.setApiKey("sk-real");
        prjXPConfig.getChatModels().add(saved);

        when(modelLister.listModels(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        globalsService.listChatModels("openai", "http://x", "********", "default");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(modelLister).listModels(eq("openai"), eq("http://x"), keyCaptor.capture(), eq(30));
        assertThat(keyCaptor.getValue()).isEqualTo("sk-real");
    }

    @Test
    void listEmbeddingModels_delegatesToLister() {
        when(modelLister.listModels("ollama", "http://x", null, 30))
                .thenReturn(List.of("mxbai-embed-large", "nomic-embed-text"));

        List<String> names = globalsService.listEmbeddingModels("ollama", "http://x", null);

        assertThat(names).containsExactly("mxbai-embed-large", "nomic-embed-text");
    }

    @Test
    void listEmbeddingModels_maskedKeyResolved() {
        PrjXPEmbeddingModelReference saved = new PrjXPEmbeddingModelReference();
        saved.setApiKey("sk-emb");
        prjXPConfig.setEmbeddingModel(saved);

        when(modelLister.listModels(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        globalsService.listEmbeddingModels("openai", "http://x", "********");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(modelLister).listModels(eq("openai"), eq("http://x"), keyCaptor.capture(), eq(30));
        assertThat(keyCaptor.getValue()).isEqualTo("sk-emb");
    }
}
