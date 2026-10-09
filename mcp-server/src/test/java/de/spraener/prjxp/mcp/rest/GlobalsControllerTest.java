package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalsControllerTest {

    private MockMvc mockMvc;
    private GlobalsService globalsService;
    private PrjXPChatModelReference sampleModel;
    private Path yamlPath;

    @BeforeEach
    void setUp() throws Exception {
        globalsService = mock(GlobalsService.class);

        sampleModel = new PrjXPChatModelReference();
        sampleModel.setStereoType("default");
        sampleModel.setServerType("ollama");
        sampleModel.setModelName("llama3");
        sampleModel.setProviderUrl("http://localhost:11434");
        sampleModel.setTimeoutSecs(60);
        sampleModel.setTemperature(0.7);

        yamlPath = Files.createTempFile("test-application", ".yaml");

        GlobalsController controller = new GlobalsController(globalsService);
        mockMvc = standaloneSetup(controller).build();
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(yamlPath);
    }

    @Test
    void getChatModels_returnsList() throws Exception {
        sampleModel.setApiKey("sk-real-secret");
        when(globalsService.getChatModels()).thenReturn(List.of(sampleModel));

        mockMvc.perform(get("/api/globals/chat-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stereoType").value("default"))
                .andExpect(jsonPath("$[0].serverType").value("ollama"))
                .andExpect(jsonPath("$[0].modelName").value("llama3"))
                .andExpect(jsonPath("$[0].apiKey").value("********"));
    }

    @Test
    void getChatModels_keepsEnvPlaceholderForApiKey() throws Exception {
        sampleModel.setApiKey("${OPENAI_API_KEY}");
        when(globalsService.getChatModels()).thenReturn(List.of(sampleModel));

        mockMvc.perform(get("/api/globals/chat-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKey").value("${OPENAI_API_KEY}"));
    }

    @Test
    void getChatModels_emptyList() throws Exception {
        when(globalsService.getChatModels()).thenReturn(List.of());

        mockMvc.perform(get("/api/globals/chat-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void createChatModel_createsNew() throws Exception {
        when(globalsService.createChatModel(any(PrjXPChatModelReference.class))).thenReturn(sampleModel);

        String body = """
                {
                    "stereoType": "coder",
                    "serverType": "lm-studio",
                    "modelName": "gemma-4-31b",
                    "providerUrl": "http://localhost:1234"
                }
                """;

        mockMvc.perform(post("/api/globals/chat-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stereoType").value("default"));

        verify(globalsService).createChatModel(any(PrjXPChatModelReference.class));
    }

    @Test
    void updateChatModel_updatesExisting() throws Exception {
        when(globalsService.updateChatModel(eq("default"), any(GlobalsService.PrjXPChatModelRequest.class)))
                .thenReturn(sampleModel);

        String body = """
                {
                    "modelName": "llama3.1",
                    "temperature": 0.8
                }
                """;

        mockMvc.perform(put("/api/globals/chat-models/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stereoType").value("default"));

        verify(globalsService).updateChatModel(eq("default"), any(GlobalsService.PrjXPChatModelRequest.class));
    }

    @Test
    void deleteChatModel_deletes() throws Exception {
        doNothing().when(globalsService).deleteChatModel("default");

        mockMvc.perform(delete("/api/globals/chat-models/default"))
                .andExpect(status().isOk());

        verify(globalsService).deleteChatModel("default");
    }

    @Test
    void getServerTypes_returnsList() throws Exception {
        when(globalsService.getServerTypes()).thenReturn(List.of("ollama", "openapi", "lm-studio", "gemini", "custom"));

        mockMvc.perform(get("/api/globals/server-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("ollama"))
                .andExpect(jsonPath("$[1]").value("openapi"))
                .andExpect(jsonPath("$[4]").value("custom"));
    }

    @Test
    void createChatModel_withArgs() throws Exception {
        PrjXPChatModelReference modelWithArgs = new PrjXPChatModelReference();
        modelWithArgs.setStereoType("custom-model");
        modelWithArgs.setServerType("custom");
        modelWithArgs.setModelName("my-model");
        modelWithArgs.setProviderUrl("http://custom.example.com");
        modelWithArgs.setArgs(Map.of("header", "value"));

        when(globalsService.createChatModel(any(PrjXPChatModelReference.class))).thenReturn(modelWithArgs);

        String body = """
                {
                    "stereoType": "custom-model",
                    "serverType": "custom",
                    "modelName": "my-model",
                    "providerUrl": "http://custom.example.com",
                    "args": {"header": "value"}
                }
                """;

        mockMvc.perform(post("/api/globals/chat-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.args.header").value("value"));
    }

    @Test
    void getEmbeddingModel_returnsDefaultWhenNotConfigured() throws Exception {
        PrjXPEmbeddingModelReference defaultModel = new PrjXPEmbeddingModelReference();
        when(globalsService.getEmbeddingModel()).thenReturn(defaultModel);

        mockMvc.perform(get("/api/globals/embedding-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverType").value("ollama"))
                .andExpect(jsonPath("$.timeoutSecs").value(60));
    }

    @Test
    void getEmbeddingModel_returnsConfiguredModel() throws Exception {
        PrjXPEmbeddingModelReference configured = new PrjXPEmbeddingModelReference();
        configured.setServerType("openai");
        configured.setProviderUrl("https://api.openai.com/v1");
        configured.setModelName("text-embedding-3-small");
        configured.setApiKey("sk-test-key");
        configured.setTimeoutSecs(30);
        when(globalsService.getEmbeddingModel()).thenReturn(configured);

        mockMvc.perform(get("/api/globals/embedding-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverType").value("openai"))
                .andExpect(jsonPath("$.providerUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.modelName").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.apiKey").value("********"))
                .andExpect(jsonPath("$.timeoutSecs").value(30));
    }

    @Test
    void updateEmbeddingModel_savesAndPersists() throws Exception {
        PrjXPEmbeddingModelReference saved = new PrjXPEmbeddingModelReference();
        saved.setServerType("gemini");
        saved.setProviderUrl("https://generativelanguage.googleapis.com/v1beta");
        saved.setModelName("embedding-gecko-001");
        saved.setApiKey("gemini-key-123");
        saved.setTimeoutSecs(45);
        when(globalsService.updateEmbeddingModel(any(GlobalsService.PrjXPEmbeddingModelRequest.class)))
                .thenReturn(saved);

        String body = """
                {
                    "serverType": "gemini",
                    "providerUrl": "https://generativelanguage.googleapis.com/v1beta",
                    "modelName": "embedding-gecko-001",
                    "apiKey": "gemini-key-123",
                    "timeoutSecs": 45
                }
                """;

        mockMvc.perform(put("/api/globals/embedding-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverType").value("gemini"))
                .andExpect(jsonPath("$.modelName").value("embedding-gecko-001"));

        verify(globalsService).updateEmbeddingModel(any(GlobalsService.PrjXPEmbeddingModelRequest.class));
    }

    @Test
    void updateEmbeddingModel_partialUpdate() throws Exception {
        PrjXPEmbeddingModelReference saved = new PrjXPEmbeddingModelReference();
        saved.setServerType("ollama");
        saved.setModelName("nomic-embed-text");
        when(globalsService.updateEmbeddingModel(any(GlobalsService.PrjXPEmbeddingModelRequest.class)))
                .thenReturn(saved);

        String body = """
                {
                    "modelName": "nomic-embed-text"
                }
                """;

        mockMvc.perform(put("/api/globals/embedding-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("nomic-embed-text"));

        verify(globalsService).updateEmbeddingModel(any(GlobalsService.PrjXPEmbeddingModelRequest.class));
    }

    @Test
    void getChatModels_returnsPromptUnmasked() throws Exception {
        sampleModel.setPrompt("Be concise.");
        when(globalsService.getChatModels()).thenReturn(List.of(sampleModel));

        mockMvc.perform(get("/api/globals/chat-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prompt").value("Be concise."));
    }

    @Test
    void updateChatModel_passesPromptToService() throws Exception {
        when(globalsService.updateChatModel(eq("default"), any(GlobalsService.PrjXPChatModelRequest.class)))
                .thenReturn(sampleModel);

        String body = """
                { "prompt": "Be concise." }
                """;

        mockMvc.perform(put("/api/globals/chat-models/default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<GlobalsService.PrjXPChatModelRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GlobalsService.PrjXPChatModelRequest.class);
        verify(globalsService).updateChatModel(eq("default"), captor.capture());
        assertThat(captor.getValue().getPrompt()).isEqualTo("Be concise.");
    }
}
