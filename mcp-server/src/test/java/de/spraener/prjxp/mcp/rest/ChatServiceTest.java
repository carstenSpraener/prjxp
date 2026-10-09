package de.spraener.prjxp.mcp.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import de.spraener.prjxp.mcp.rest.dto.ChatRequest;
import de.spraener.prjxp.mcp.rest.dto.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.langchain4j.model.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private KIChatProvider kiChatProvider;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private LuceneEmbeddingStore luceneStore;

    @Mock
    private PrjXPConfig prjXPConfig;

    @Mock
    private SseEmitter sseEmitter;

    @Mock
    private PrjxpMcpTool mcpTool;

    private ObjectMapper objectMapper;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        lenient().when(prjXPConfig.getProjects()).thenReturn(new ArrayList<>());
        objectMapper = new ObjectMapper();
        chatService = new ChatService(kiChatProvider, embeddingModel, luceneStore, prjXPConfig, objectMapper);
        // Inject the mocked MCP tool via reflection (since constructor doesn't take it)
        injectMcpTool();
    }

    /** Inject the mocked PrjxpMcpTool into ChatService via reflection. */
    private void injectMcpTool() {
        try {
            java.lang.reflect.Field field = ChatService.class.getDeclaredField("mcpTool");
            field.setAccessible(true);
            field.set(chatService, mcpTool);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mcpTool mock", e);
        }
    }

    private String buildPrompt(ChatRequest request) throws Exception {
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(chatService, request);
    }

    private void injectHubRegistry(HubProjectRegistry registry) {
        try {
            java.lang.reflect.Field field = ChatService.class.getDeclaredField("hubProjectRegistry");
            field.setAccessible(true);
            field.set(chatService, registry);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject hubProjectRegistry mock", e);
        }
    }

    @Test
    void buildEnrichedPromptIncludesModelPrompt() throws Exception {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test-model");
        model.setPrompt("Be concise.");
        when(prjXPConfig.getChatModels()).thenReturn(List.of(model));

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");

        String prompt = buildPrompt(request);

        assertThat(prompt).contains("MODEL INSTRUCTIONS:\nBe concise.");
    }

    @Test
    void buildEnrichedPromptIncludesProjectPromptViaConfigFallback() throws Exception {
        ProjectDefinition def = new ProjectDefinition();
        def.setName("my-project");
        def.setPrompt("Focus on payments.");
        when(prjXPConfig.getProjectDefinition("my-project")).thenReturn(Optional.of(def));

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setProjectContext("my-project");

        String prompt = buildPrompt(request);

        assertThat(prompt).contains("PROJECT INSTRUCTIONS:\nFocus on payments.");
    }

    @Test
    void buildEnrichedPromptProjectBeforeModelAndBothBeforeQuestion() throws Exception {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test-model");
        model.setPrompt("Be concise.");
        when(prjXPConfig.getChatModels()).thenReturn(List.of(model));

        ProjectDefinition def = new ProjectDefinition();
        def.setName("my-project");
        def.setPrompt("Focus on payments.");
        when(prjXPConfig.getProjectDefinition("my-project")).thenReturn(Optional.of(def));

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");
        request.setProjectContext("my-project");

        String prompt = buildPrompt(request);

        int projectIdx = prompt.indexOf("PROJECT INSTRUCTIONS");
        int modelIdx = prompt.indexOf("MODEL INSTRUCTIONS");
        int questionIdx = prompt.indexOf("USER QUESTION:");
        assertThat(projectIdx).isLessThan(modelIdx);
        assertThat(modelIdx).isLessThan(questionIdx);
    }

    @Test
    void buildEnrichedPromptResolvesEnvPlaceholderInPrompts() throws Exception {
        PrjXPChatModelReference model = new PrjXPChatModelReference();
        model.setStereoType("test-model");
        model.setPrompt("${UNSET_PRJXP_CHAT_VAR:fallback-text}");
        when(prjXPConfig.getChatModels()).thenReturn(List.of(model));

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");

        String prompt = buildPrompt(request);

        assertThat(prompt).contains("fallback-text");
    }

    @Test
    void buildEnrichedPromptUsesHubRegistryForProjectPrompt() throws Exception {
        ProjectDefinition def = new ProjectDefinition();
        def.setName("my-project");
        def.setPrompt("Registry prompt.");

        ProjectEntry entry = new ProjectEntry("my-project", Path.of("/tmp/x"),
                ProjectEntry.Kind.LIVE, def, null);
        HubProjectRegistry registry = mock(HubProjectRegistry.class);
        when(registry.entry("my-project")).thenReturn(Optional.of(entry));
        injectHubRegistry(registry);

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setProjectContext("my-project");

        String prompt = buildPrompt(request);

        assertThat(prompt).contains("Registry prompt.");
    }

    @Test
    void buildEnrichedPromptWithoutPromptsIsUnchanged() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");

        String prompt = buildPrompt(request);

        assertThat(prompt).doesNotContain("INSTRUCTIONS");
    }

    @Test
    void buildEnrichedPromptIncludesConversationHistory() throws Exception {
        // Given: a request with conversation history
        List<ChatRequest.ChatMessage> history = new ArrayList<>();
        history.add(new ChatRequest.ChatMessage("user", "What does this system do?"));
        history.add(new ChatRequest.ChatMessage("assistant", "It's a RAG system for code projects."));

        ChatRequest request = new ChatRequest();
        request.setMessage("And what is a PxChunk?");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);
        request.setMessages(history);

        // When: invoke buildEnrichedPrompt via reflection (private method)
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(chatService, request);

        // Then: verify the prompt contains conversation history and both messages
        assertThat(prompt).contains("=== CONVERSATION HISTORY ===");
        assertThat(prompt).contains("===========================");
        assertThat(prompt).contains("USER: What does this system do?");
        assertThat(prompt).contains("ASSISTANT: It's a RAG system for code projects.");
        assertThat(prompt).contains("USER QUESTION: And what is a PxChunk?");
    }

    @Test
    void buildEnrichedPromptWithoutConversationHistory() throws Exception {
        // Given: a request without conversation history
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);

        // When: invoke buildEnrichedPrompt via reflection
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(chatService, request);

        // Then: no conversation history section should be present
        assertThat(prompt).doesNotContain("CONVERSATION HISTORY");
        assertThat(prompt).contains("USER QUESTION: Hello");
    }

    @Test
    void buildEnrichedPromptWithEmptyConversationHistory() throws Exception {
        // Given: a request with an empty messages list
        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);
        request.setMessages(new ArrayList<>());

        // When: invoke buildEnrichedPrompt via reflection
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(chatService, request);

        // Then: no conversation history section should be present
        assertThat(prompt).doesNotContain("CONVERSATION HISTORY");
        assertThat(prompt).contains("USER QUESTION: Hello");
    }

    @Test
    void chatRequestChatMessageConstructor() {
        ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage("user", "Hello");
        assertThat(msg.getRole()).isEqualTo("user");
        assertThat(msg.getContent()).isEqualTo("Hello");
    }

    @Test
    void chatRequestChatMessageDefaultConstructor() {
        ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage();
        assertThat(msg.getRole()).isNull();
        assertThat(msg.getContent()).isNull();

        msg.setRole("assistant");
        msg.setContent("Hi there");
        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isEqualTo("Hi there");
    }

    @Test
    void chatRequestMessagesDefaultEmpty() {
        ChatRequest request = new ChatRequest();
        assertThat(request.getMessages()).isEmpty();
    }

    @Test
    void chatRequestSetMessages() {
        ChatRequest request = new ChatRequest();
        List<ChatRequest.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatRequest.ChatMessage("user", "Test"));
        request.setMessages(msgs);

        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("Test");
    }

    @Test
    void buildEnrichedPromptWithMultipleHistoryMessages() throws Exception {
        // Given: a request with multiple conversation history messages
        List<ChatRequest.ChatMessage> history = new ArrayList<>();
        history.add(new ChatRequest.ChatMessage("user", "First question"));
        history.add(new ChatRequest.ChatMessage("assistant", "First answer"));
        history.add(new ChatRequest.ChatMessage("user", "Second question"));
        history.add(new ChatRequest.ChatMessage("assistant", "Second answer"));

        ChatRequest request = new ChatRequest();
        request.setMessage("Third question");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);
        request.setMessages(history);

        // When: invoke buildEnrichedPrompt via reflection
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(chatService, request);

        // Then: all history messages should be present in order
        assertThat(prompt).contains("USER: First question");
        assertThat(prompt).contains("ASSISTANT: First answer");
        assertThat(prompt).contains("USER: Second question");
        assertThat(prompt).contains("ASSISTANT: Second answer");
        assertThat(prompt).contains("USER QUESTION: Third question");

        // Verify order: conversation history comes before user question
        int historyIndex = prompt.indexOf("CONVERSATION HISTORY");
        int questionIndex = prompt.indexOf("USER QUESTION: Third question");
        assertThat(historyIndex).isLessThan(questionIndex);
    }

    // --- sendStreaming tests ---

    @Test
    void sendStreamingSendsPhaseEventsAndComplete() {
        // Given: a request without project context (no RAG) and no tools
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));
        when(mockChat.chat(anyString())).thenReturn("Hello, how can I help?");

        ChatRequest request = new ChatRequest();
        request.setMessage("Hi there");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify streaming completed successfully
        verify(sseEmitter).complete();
        verify(sseEmitter, never()).completeWithError(any());
    }

    @Test
    void sendStreamingSendsRagPhaseWhenProjectContextIsSet() {
        // Given: a request with project context
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));
        when(mockChat.chat(anyString())).thenReturn("Answer with RAG context");

        ChatRequest request = new ChatRequest();
        request.setMessage("What is RAG?");
        request.setChatModelName("test-model");
        request.setProjectContext("my-project");
        request.setUseTools(false);

        // When: call sendStreaming (RAG will fail due to mocks, but phase event should still be sent)
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify streaming completed (RAG phase was processed)
        verify(sseEmitter).complete();
    }

    @Test
    void sendStreamingSendsToolsPhaseWhenUseToolsIsTrue() {
        // Given: a request with useTools enabled
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));
        when(mockChat.chat(anyString())).thenReturn("Answer with tools");

        ChatRequest request = new ChatRequest();
        request.setMessage("List projects");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(true);

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify streaming completed (tools phase was processed)
        verify(sseEmitter).complete();
    }

    @Test
    void sendStreamingSendsErrorEventOnException() {
        // Given: a model that doesn't exist
        when(kiChatProvider.getByStereotype("missing-model")).thenReturn(Optional.empty());

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("missing-model");

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify error was reported
        verify(sseEmitter).completeWithError(any(Exception.class));
    }

    @Test
    void sendStreamingCompleteEventContainsResponseContent() throws Exception {
        // Given: a working chat model
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));
        when(mockChat.chat(anyString())).thenReturn("The answer is 42");

        ChatRequest request = new ChatRequest();
        request.setMessage("What is the answer?");
        request.setChatModelName("test-model");

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify streaming completed successfully with response
        verify(sseEmitter).complete();
        verify(sseEmitter, never()).completeWithError(any());
    }

    // --- Tool Integration Tests ---

    @Test
    void parseToolCallsSingleVectorSearch() throws Exception {
        // Given: an LLM response with a single TOOL_CALL marker
        String llmResponse = "Based on your question, let me search the codebase.\n" +
                "TOOL_CALL[vectorSearch](userQuestion=\"CodeTarget class and its methods\", projectName=\"cgV19\")\n" +
                "This should give us the relevant information.";

        // When: parse tool calls via reflection
        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> calls = (List<?>) method.invoke(chatService, llmResponse);

        // Then: one tool call should be parsed
        assertThat(calls).hasSize(1);

        // Access the ToolCallRequest fields via reflection
        Object call = calls.get(0);
        Method getToolName = call.getClass().getDeclaredMethod("getToolName");
        Method getParams = call.getClass().getDeclaredMethod("getParams");

        assertThat(getToolName.invoke(call)).isEqualTo("vectorSearch");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> params = (java.util.Map<String, String>) getParams.invoke(call);
        assertThat(params).containsEntry("userQuestion", "CodeTarget class and its methods");
        assertThat(params).containsEntry("projectName", "cgV19");
    }

    @Test
    void parseToolCallsMultipleTools() throws Exception {
        // Given: an LLM response with multiple TOOL_CALL markers
        String llmResponse = "Let me search and then read the file.\n" +
                "TOOL_CALL[vectorSearch](userQuestion=\"find the main class\")\n" +
                "Some text in between.\n" +
                "TOOL_CALL[readFile](file=\"src/main/java/Main.java\", project=\"my-project\")";

        // When: parse tool calls
        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> calls = (List<?>) method.invoke(chatService, llmResponse);

        // Then: two tool calls should be parsed
        assertThat(calls).hasSize(2);

        Object call1 = calls.get(0);
        Method getToolName = call1.getClass().getDeclaredMethod("getToolName");
        assertThat(getToolName.invoke(call1)).isEqualTo("vectorSearch");

        Object call2 = calls.get(1);
        assertThat(getToolName.invoke(call2)).isEqualTo("readFile");
    }

    @Test
    void parseToolCallsNoMarkers() throws Exception {
        // Given: an LLM response without any TOOL_CALL markers
        String llmResponse = "The answer to your question is 42. No tools needed.";

        // When: parse tool calls
        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<?> calls = (List<?>) method.invoke(chatService, llmResponse);

        // Then: no tool calls should be found
        assertThat(calls).isEmpty();
    }

    @Test
    void executeToolCallListProjects() throws Exception {
        // Given: mcpTool.listProjects() returns a list of projects
        when(mcpTool.listProjects()).thenReturn(List.of("project-a", "project-b"));

        // When: execute listProjects tool call
        ChatService.ToolCallRequest request = new ChatService.ToolCallRequest("listProjects", java.util.Map.of());
        Method method = getExecuteToolCallMethod();
        method.setAccessible(true);

        String result = (String) method.invoke(chatService, request);

        // Then: the result should contain both project names
        assertThat(result).contains("project-a");
        assertThat(result).contains("project-b");
    }

    @Test
    void executeToolCallUnknownTool() throws Exception {
        // When: execute an unknown tool call
        Class<?> toolCallReqClass = getToolCallRequestClass();
        java.lang.reflect.Constructor<?> ctor = toolCallReqClass.getDeclaredConstructor(String.class, java.util.Map.class);
        ctor.setAccessible(true);
        Object request = ctor.newInstance("unknownTool", java.util.Map.of());

        Method method = ChatService.class.getDeclaredMethod("executeToolCall", toolCallReqClass);
        method.setAccessible(true);
        String result = (String) method.invoke(chatService, request);

        // Then: the result should indicate an unknown tool
        assertThat(result).contains("Unknown tool");
        assertThat(result).contains("unknownTool");
    }

    @Test
    void executeToolCallNoMcpToolAvailable() throws Exception {
        // Given: mcpTool is null (not injected)
        try {
            java.lang.reflect.Field field = ChatService.class.getDeclaredField("mcpTool");
            field.setAccessible(true);
            field.set(chatService, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mcpTool to null", e);
        }

        // When: execute any tool call
        Class<?> toolCallReqClass = getToolCallRequestClass();
        java.lang.reflect.Constructor<?> ctor = toolCallReqClass.getDeclaredConstructor(String.class, java.util.Map.class);
        ctor.setAccessible(true);
        Object request = ctor.newInstance("vectorSearch", java.util.Map.of("userQuestion", "test"));

        Method method = ChatService.class.getDeclaredMethod("executeToolCall", toolCallReqClass);
        method.setAccessible(true);
        String result = (String) method.invoke(chatService, request);

        // Then: the result should indicate MCP tool is not available
        assertThat(result).contains("not available");
    }

    @Test
    void sendStreamingWithToolsRunsAgenticLoop() {
        // Given: LLM first returns a tool call, then a final answer on second call
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));

        // First call returns a tool call
        when(mockChat.chat(anyString())).thenReturn(
                "Let me search for that.\nTOOL_CALL[vectorSearch](userQuestion=\"find something\")");
        // Second call returns final answer (no tool calls)
        when(mockChat.chat(anyString())).thenReturn("The search found relevant code in Main.java.");

        ChatRequest request = new ChatRequest();
        request.setMessage("Find the main class");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(true);

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: verify the LLM was called (at least once) and streaming completed
        verify(mockChat, atLeast(1)).chat(anyString());
        verify(sseEmitter).complete();
    }

    @Test
    void sendStreamingWithoutToolsSkipsAgenticLoop() {
        // Given: useTools is false
        KIChat mockChat = mock(KIChat.class);
        when(kiChatProvider.getByStereotype("test-model")).thenReturn(Optional.of(mockChat));
        when(mockChat.chat(anyString())).thenReturn("Simple answer without tools");

        ChatRequest request = new ChatRequest();
        request.setMessage("Hello");
        request.setChatModelName("test-model");
        request.setProjectContext(null);
        request.setUseTools(false);

        // When: call sendStreaming
        chatService.sendStreaming(request, sseEmitter);

        // Then: LLM called only once, no tool execution
        verify(mockChat).chat(anyString());
        verify(mcpTool, never()).vectorSearch(anyString(), any(), anyDouble(), anyInt(), anyBoolean());
        verify(sseEmitter).complete();
    }

    @Test
    void getAvailableToolsReturnsRealToolNames() {
        // When: get available tools
        List<String> tools = chatService.getAvailableTools();

        // Then: should return the 5 real MCP tool names
        assertThat(tools).containsExactly("vectorSearch", "grep", "readFile", "readBySignature", "listProjects");
    }

    // --- Helper methods for reflection-based tests ---

    /** Get the ToolCallRequest inner class via reflection. */
    private Class<?> getToolCallRequestClass() {
        for (Class<?> inner : ChatService.class.getDeclaredClasses()) {
            if ("ToolCallRequest".equals(inner.getSimpleName())) {
                return inner;
            }
        }
        throw new RuntimeException("ToolCallRequest class not found");
    }

    /** Get the executeToolCall method via reflection. */
    private Method getExecuteToolCallMethod() {
        try {
            return ChatService.class.getDeclaredMethod("executeToolCall", ChatService.ToolCallRequest.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("executeToolCall method not found", e);
        }
    }
}
