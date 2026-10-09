package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.rest.dto.ChatRequest;
import de.spraener.prjxp.mcp.rest.dto.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ChatControllerTest {

    private MockMvc mockMvc;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = org.mockito.Mockito.mock(ChatService.class);
        mockMvc = standaloneSetup(new ChatController(chatService)).build();
    }

    @Test
    void sendMessage_returnsAssistantResponse() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("How does RAG work?");
        request.setChatModelName("default");

        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        response.setContent("RAG uses retrieval-augmented generation to enrich prompts with project context.");

        when(chatService.send(any(ChatRequest.class))).thenReturn(response);

        String body = """
                {
                    "message": "How does RAG work?",
                    "chatModelName": "default"
                }
                """;

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("RAG uses retrieval-augmented generation to enrich prompts with project context."));
    }

    @Test
    void sendMessage_withProjectContext() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("Explain the architecture");
        request.setChatModelName("default");
        request.setProjectContext("prjxp");

        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        response.setContent("The architecture uses a modular design with golden-retriever for RAG.");

        when(chatService.send(any(ChatRequest.class))).thenReturn(response);

        String body = """
                {
                    "message": "Explain the architecture",
                    "chatModelName": "default",
                    "projectContext": "prjxp"
                }
                """;

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"));
    }

    @Test
    void sendMessage_withUseTools() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("Search for chunk-norris");
        request.setChatModelName("default");
        request.setUseTools(true);

        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        response.setContent("I found relevant chunks about chunk-norris.");

        when(chatService.send(any(ChatRequest.class))).thenReturn(response);

        String body = """
                {
                    "message": "Search for chunk-norris",
                    "chatModelName": "default",
                    "useTools": true
                }
                """;

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"));
    }

    @Test
    void getAvailableProjects_returnsList() throws Exception {
        when(chatService.getAvailableProjects()).thenReturn(List.of("prjxp", "my-project"));

        mockMvc.perform(post("/api/chat/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("prjxp"))
                .andExpect(jsonPath("$[1]").value("my-project"));
    }

    @Test
    void getAvailableProjects_emptyList() throws Exception {
        when(chatService.getAvailableProjects()).thenReturn(List.of());

        mockMvc.perform(post("/api/chat/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getAvailableTools_returnsList() throws Exception {
        when(chatService.getAvailableTools()).thenReturn(List.of("project-search", "code-query"));

        mockMvc.perform(post("/api/chat/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("project-search"))
                .andExpect(jsonPath("$[1]").value("code-query"));
    }

    @Test
    void sendMessage_invalidModel_throwsError() throws Exception {
        when(chatService.send(any(ChatRequest.class)))
                .thenThrow(new IllegalArgumentException("Chat model 'unknown' not found"));

        String body = """
                {
                    "message": "Hello",
                    "chatModelName": "unknown"
                }
                """;

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
