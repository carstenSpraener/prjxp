package de.spraener.prjxp.mcp.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalsControllerModelTestTest {

    private GlobalsService globalsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        globalsService = mock(GlobalsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GlobalsController(globalsService)).build();
    }

    @Test
    void testChatModel_endpoint() throws Exception {
        when(globalsService.testChatModel(any())).thenReturn(
                ModelTestResult.success("Chat test succeeded", 42, "Hello!", null));

        mockMvc.perform(post("/api/globals/chat-models/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stereoType":"default","serverType":"ollama","modelName":"llama3","providerUrl":"http://localhost:11434"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response").value("Hello!"));
    }

    @Test
    void testEmbeddingModel_endpoint() throws Exception {
        when(globalsService.testEmbeddingModel(any())).thenReturn(
                ModelTestResult.success("Embedding test succeeded", 17, null, 1024));

        mockMvc.perform(post("/api/globals/embedding-model/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serverType":"ollama","providerUrl":"http://localhost:11434","modelName":"mxbai-embed-large"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dimensions").value(1024));
    }

    @Test
    void listChatModels_endpoint() throws Exception {
        when(globalsService.listChatModels(eq("ollama"), eq("http://x"), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of("a", "b"));

        mockMvc.perform(get("/api/globals/chat-models/models")
                        .param("serverType", "ollama")
                        .param("providerUrl", "http://x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("a"))
                .andExpect(jsonPath("$[1]").value("b"));
    }

    @Test
    void listEmbeddingModels_endpoint() throws Exception {
        when(globalsService.listEmbeddingModels(eq("ollama"), eq("http://x"), nullable(String.class)))
                .thenReturn(List.of("mxbai-embed-large"));

        mockMvc.perform(get("/api/globals/embedding-model/models")
                        .param("serverType", "ollama")
                        .param("providerUrl", "http://x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mxbai-embed-large"));
    }

    @Test
    void listChatModels_customType_returns400() throws Exception {
        when(globalsService.listChatModels(anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenThrow(new IllegalArgumentException("Model listing is not supported for server type 'custom'"));

        mockMvc.perform(get("/api/globals/chat-models/models")
                        .param("serverType", "custom")
                        .param("providerUrl", "http://x"))
                .andExpect(status().isBadRequest());
    }
}
