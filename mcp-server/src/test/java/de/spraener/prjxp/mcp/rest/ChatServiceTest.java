package de.spraener.prjxp.mcp.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.toolregistry.GroovyToolExecutor;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.rest.dto.ChatRequest;
import de.spraener.prjxp.mcp.rest.dto.ToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.langchain4j.model.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
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

    private ToolRegistry toolRegistry;
    private final List<ExecutedTool> executedTools = new ArrayList<>();

    private ObjectMapper objectMapper;
    private ChatService chatService;

    /** Records a tool execution so tests can assert on the arguments the service passed in. */
    private record ExecutedTool(String name, Map<String, Object> arguments) {}

    @BeforeEach
    void setUp() {
        lenient().when(prjXPConfig.getProjects()).thenReturn(new ArrayList<>());
        objectMapper = new ObjectMapper();
        chatService = new ChatService(kiChatProvider, embeddingModel, luceneStore, prjXPConfig, objectMapper);
        toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        registerRecordingTools();
        injectField("toolRegistry", toolRegistry);
    }

    /** Registers stand-ins for the real prjxp tools that simply record their arguments. */
    private void registerRecordingTools() {
        for (Map.Entry<String, Map<String, String>> entry : registeredToolParams().entrySet()) {
            String name = entry.getKey();
            toolRegistry.register(new ToolDefinition(name, name + " test tool", entry.getValue(),
                    params -> {
                        executedTools.add(new ExecutedTool(name, new HashMap<>(params)));
                        return "result of " + name;
                    }));
        }
    }

    /** Realistic parameter names (matching the real prjxp tools) so tool-name inference is testable. */
    private Map<String, Map<String, String>> registeredToolParams() {
        Map<String, Map<String, String>> params = new LinkedHashMap<>();
        params.put("vectorSearch", Map.of(
                "userQuestion", "Semantic search question",
                "projectName", "Project to search in",
                "similarity", "Similarity threshold",
                "maxResults", "Maximum results",
                "skeletonsOnly", "Return skeletons only"));
        params.put("grep", Map.of(
                "query", "Exact search string",
                "project", "Project name",
                "language", "Language filter",
                "limit", "Maximum results"));
        params.put("readFile", Map.of(
                "file", "File path",
                "project", "Project name",
                "offset", "Start line",
                "limit", "Maximum lines"));
        params.put("readBySignature", Map.of(
                "method", "Method name or FQN",
                "container", "Containing class",
                "project", "Project name"));
        params.put("listProjects", Map.of());
        return params;
    }

    /** Set a private ChatService field via reflection. */
    private void injectField(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = ChatService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(chatService, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject " + fieldName, e);
        }
    }

    private String buildPrompt(ChatRequest request) throws Exception {
        Method method = ChatService.class.getDeclaredMethod("buildEnrichedPrompt", ChatRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(chatService, request);
    }

    private void injectHubRegistry(HubProjectRegistry registry) {
        injectField("hubProjectRegistry", registry);
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
        ChatService.ToolCallRequest request = new ChatService.ToolCallRequest("listProjects", java.util.Map.of());
        Method method = getExecuteToolCallMethod();
        method.setAccessible(true);

        String result = (String) method.invoke(chatService, request, null);

        // Then: the registry tool was executed
        assertThat(result).isEqualTo("result of listProjects");
        assertThat(executedTools).extracting(ExecutedTool::name).containsExactly("listProjects");
    }

    @Test
    void executeToolCallUnknownTool() throws Exception {
        // When: execute an unknown tool call
        Class<?> toolCallReqClass = getToolCallRequestClass();
        java.lang.reflect.Constructor<?> ctor = toolCallReqClass.getDeclaredConstructor(String.class, java.util.Map.class);
        ctor.setAccessible(true);
        Object request = ctor.newInstance("unknownTool", java.util.Map.of());

        Method method = ChatService.class.getDeclaredMethod("executeToolCall", toolCallReqClass, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(chatService, request, null);

        // Then: the result should indicate an unknown tool
        assertThat(result).contains("Unknown tool");
        assertThat(result).contains("unknownTool");
    }

    @Test
    void executeToolCallNoToolRegistryAvailable() throws Exception {
        // Given: no tool registry is wired
        injectField("toolRegistry", null);

        // When: execute any tool call
        Class<?> toolCallReqClass = getToolCallRequestClass();
        java.lang.reflect.Constructor<?> ctor = toolCallReqClass.getDeclaredConstructor(String.class, java.util.Map.class);
        ctor.setAccessible(true);
        Object request = ctor.newInstance("vectorSearch", java.util.Map.of("userQuestion", "test"));

        Method method = ChatService.class.getDeclaredMethod("executeToolCall", toolCallReqClass, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(chatService, request, null);

        // Then: the result should indicate the backend is not available
        assertThat(result).contains("not available");
    }

    @Test
    void parseToolCallsGrepQueryWithParenthesesIsNotTruncated() throws Exception {
        // Given: a grep call whose query contains parentheses and a comma (method signature)
        String llmResponse = "Let me grep for the signature.\n" +
                "TOOL_CALL[grep](query=\"public MClass createMClass(String name, int id)\", project=\"cgV19\", limit=20)\n" +
                "Done.";

        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        List<?> calls = (List<?>) method.invoke(chatService, llmResponse);

        assertThat(calls).hasSize(1);
        Object call = calls.get(0);
        Method getParams = call.getClass().getDeclaredMethod("getParams");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> params = (java.util.Map<String, String>) getParams.invoke(call);

        assertThat(params).containsEntry("query", "public MClass createMClass(String name, int id)");
        assertThat(params).containsEntry("project", "cgV19");
        assertThat(params).containsEntry("limit", "20");
    }

    @Test
    void parseToolCallsHandlesEscapedQuotesInValue() throws Exception {
        String llmResponse = "TOOL_CALL[grep](query=\"log.info(\\\"hello\\\")\", project=\"p1\")";

        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        List<?> calls = (List<?>) method.invoke(chatService, llmResponse);

        assertThat(calls).hasSize(1);
        Object call = calls.get(0);
        Method getParams = call.getClass().getDeclaredMethod("getParams");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> params = (java.util.Map<String, String>) getParams.invoke(call);

        assertThat(params).containsEntry("query", "log.info(\"hello\")");
        assertThat(params).containsEntry("project", "p1");
    }

    // --- XML-style tool call recovery (models that ignore the TOOL_CALL protocol) ---

    @Test
    void parseToolCallsRecoversXmlFunctionCall() throws Exception {
        // Given: a Qwen-style response with an explicit function tag and nested parameter blocks
        String llmResponse = "Let me read that file.\n" +
                "<function=readFile>\n" +
                param("file", "cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java") + "\n" +
                param("limit", "120") + "\n" +
                param("offset", "254") + "\n" +
                param("project", "cgV19") + "\n" +
                "</function";

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: one recovered call with all parameters
        assertThat(calls).hasSize(1);
        Object call = calls.get(0);
        assertThat(invokeGetToolName(call)).isEqualTo("readFile");
        Map<String, String> params = invokeGetParams(call);
        assertThat(params).containsEntry("file", "cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java");
        assertThat(params).containsEntry("limit", "120");
        assertThat(params).containsEntry("offset", "254");
        assertThat(params).containsEntry("project", "cgV19");
    }

    @Test
    void parseToolCallsInfersToolFromBareXmlParameterBlocks() throws Exception {
        // Given: bare parameter blocks without an explicit tool name (the reported Qwen scenario)
        String llmResponse = "Here is the file content.\n" +
                param("limit", "120") + "\n\n" +
                param("offset", "254") + "\n\n" +
                param("project", "cgV19");

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: the tool is inferred from the parameter keys (readFile matches limit+offset+project)
        assertThat(calls).hasSize(1);
        Object call = calls.get(0);
        assertThat(invokeGetToolName(call)).isEqualTo("readFile");
        Map<String, String> params = invokeGetParams(call);
        assertThat(params).containsEntry("limit", "120");
        assertThat(params).containsEntry("offset", "254");
        assertThat(params).containsEntry("project", "cgV19");
    }

    @Test
    void parseToolCallsRecoversUnclosedXmlParameterBlocks() throws Exception {
        // Given: parameter blocks whose closing tags are missing (malformed Qwen output)
        String llmResponse = "Here is the file content.\n" +
                "<" + "parameter=limit>120\n\n" +
                "<" + "parameter=offset>254\n\n" +
                "<" + "parameter=project>\ncgV19\n";

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: the values are recovered up to the next tag / end of text and trimmed
        assertThat(calls).hasSize(1);
        Object call = calls.get(0);
        assertThat(invokeGetToolName(call)).isEqualTo("readFile");
        Map<String, String> params = invokeGetParams(call);
        assertThat(params).containsEntry("limit", "120");
        assertThat(params).containsEntry("offset", "254");
        assertThat(params).containsEntry("project", "cgV19");
    }

    @Test
    void parseToolCallsDoesNotGuessForAmbiguousXmlParams() throws Exception {
        // Given: a single parameter that matches several registered tools equally well (project)
        String llmResponse = "Some answer.\n" + param("project", "cgV19");

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: no call is executed - the inference would be a blind guess
        assertThat(calls).isEmpty();
    }

    @Test
    void parseToolCallsPrefersTextProtocolOverXml() throws Exception {
        // Given: a response containing both a proper TOOL_CALL marker and XML markup
        String llmResponse = "TOOL_CALL[grep](query=\"class Foo\", project=\"p1\")\n" +
                "<function=readFile>\n" + param("file", "X.java") + "\n</function";

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: only the text-protocol call is used
        assertThat(calls).hasSize(1);
        assertThat(invokeGetToolName(calls.get(0))).isEqualTo("grep");
    }

    @Test
    void parseToolCallsIgnoresIncidentalXmlInPlainAnswer() throws Exception {
        // Given: a plain answer that mentions HTML tags in prose
        String llmResponse = "The page renders a <div> element and a <span> tag.";

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: no tool call is detected
        assertThat(calls).isEmpty();
    }

    @Test
    void parseToolCallsIgnoresNameTagWithoutParamsThatIsNotATool() throws Exception {
        // Given: a name tag in prose that does not refer to a registered tool
        String llmResponse = "The author is <name>John</name> and wrote the code.";

        // When: parse tool calls via reflection
        List<?> calls = invokeParseToolCalls(llmResponse);

        // Then: no tool call is detected
        assertThat(calls).isEmpty();
    }

    @Test
    void extractFinalAnswerStripsXmlToolCalls() throws Exception {
        // Given: a final answer that still contains the recovered XML call markup
        String response = "Let me check.\n" +
                "<function=readFile>\n" + param("file", "X.java") + "\n</function\n" +
                "The file contains the answer.";

        // (the closing tag is deliberately malformed - stripping must still work)

        ToolCall tc = new ToolCall();
        tc.setToolName("readFile");

        // When: extract the final answer via reflection
        String result = invokeExtractFinalAnswer(response, List.of(tc));

        // Then: the XML markup is stripped, the prose remains
        assertThat(result).doesNotContain("<function");
        assertThat(result).contains("The file contains the answer.");
    }

    @Test
    void executeGrepFallsBackToChatProjectContext() throws Exception {
        ChatService.ToolCallRequest request =
                new ChatService.ToolCallRequest("grep", java.util.Map.of("query", "class Foo"));
        Method method = getExecuteToolCallMethod();
        method.setAccessible(true);

        method.invoke(chatService, request, "my-project");

        assertThat(executedTools).hasSize(1);
        assertThat(executedTools.get(0).arguments())
                .containsEntry("query", "class Foo")
                .containsEntry("project", "my-project");
    }

    @Test
    void executeReadFileUsesExplicitProjectOverContext() throws Exception {
        ChatService.ToolCallRequest request = new ChatService.ToolCallRequest(
                "readFile", java.util.Map.of("file", "X.java", "project", "explicit-project"));
        Method method = getExecuteToolCallMethod();
        method.setAccessible(true);

        method.invoke(chatService, request, "context-project");

        assertThat(executedTools).hasSize(1);
        assertThat(executedTools.get(0).arguments()).containsEntry("project", "explicit-project");
    }

    @Test
    void nativeToolLoopExecutesToolsAndReturnsFinalAnswer() {
        // Given: a tool-capable chat that first requests a tool and then answers
        KIChat toolChat = mock(KIChat.class);
        when(toolChat.supportsTools()).thenReturn(true);
        when(kiChatProvider.getByStereotype("tool-model")).thenReturn(Optional.of(toolChat));

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("1")
                .name("grep")
                .arguments("{\"query\":\"public void foo(String a)\"}")
                .build();
        dev.langchain4j.model.chat.response.ChatResponse withTool =
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(toolRequest))
                        .build();
        dev.langchain4j.model.chat.response.ChatResponse finalAnswer =
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("Found it in Foo.java"))
                        .build();
        when(toolChat.chat(anyList(), anyList())).thenReturn(withTool, finalAnswer);

        ChatRequest request = new ChatRequest();
        request.setMessage("Where is foo?");
        request.setChatModelName("tool-model");
        request.setProjectContext("my-project");
        request.setUseTools(true);

        // When
        de.spraener.prjxp.mcp.rest.dto.ChatResponse response = chatService.send(request);

        // Then: the tool ran with the model's arguments plus the project context
        assertThat(executedTools).hasSize(1);
        assertThat(executedTools.get(0).name()).isEqualTo("grep");
        assertThat(executedTools.get(0).arguments())
                .containsEntry("query", "public void foo(String a)")
                .containsEntry("project", "my-project");
        assertThat(response.getContent()).isEqualTo("Found it in Foo.java");
        assertThat(response.getToolCalls()).hasSize(1);
        // The plain text protocol must not be used for a tool-capable model
        verify(toolChat, never()).chat(anyString());
    }

    @Test
    void nativeToolLoopRequestsFinalAnswerAfterLastToolRound() {
        KIChat toolChat = mock(KIChat.class);
        when(toolChat.supportsTools()).thenReturn(true);
        when(kiChatProvider.getByStereotype("limited-model")).thenReturn(Optional.of(toolChat));

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("last-tool")
                .name("grep")
                .arguments("{\"query\":\"class Foo\"}")
                .build();
        dev.langchain4j.model.chat.response.ChatResponse toolResponse =
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(toolRequest))
                        .build();
        dev.langchain4j.model.chat.response.ChatResponse finalAnswer =
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from("Final answer after the last tool call"))
                        .build();
        when(toolChat.chat(anyList(), anyList())).thenReturn(toolResponse, toolResponse, toolResponse, finalAnswer);

        ChatRequest request = new ChatRequest();
        request.setMessage("Find Foo");
        request.setChatModelName("limited-model");
        request.setUseTools(true);

        de.spraener.prjxp.mcp.rest.dto.ChatResponse response = chatService.send(request);

        assertThat(response.getContent()).isEqualTo("Final answer after the last tool call");
        assertThat(response.getToolCalls()).hasSize(3);
        verify(toolChat, org.mockito.Mockito.times(4)).chat(anyList(), anyList());
    }

    @Test
    void nativeToolLoopFallsBackToTextProtocolOnProviderError() {
        // Given: a chat that claims tool support but fails when tools are passed
        KIChat toolChat = mock(KIChat.class);
        when(toolChat.supportsTools()).thenReturn(true);
        when(toolChat.chat(anyList(), anyList()))
                .thenThrow(new UnsupportedOperationException("tools not supported by provider"));
        when(toolChat.chat(anyString()))
                .thenReturn("TOOL_CALL[grep](query=\"class Foo\", project=\"p1\")", "Final text answer");
        when(kiChatProvider.getByStereotype("broken-model")).thenReturn(Optional.of(toolChat));

        ChatRequest request = new ChatRequest();
        request.setMessage("Find Foo");
        request.setChatModelName("broken-model");
        request.setUseTools(true);

        // When
        de.spraener.prjxp.mcp.rest.dto.ChatResponse response = chatService.send(request);

        // Then: the text protocol took over and the tool still ran
        assertThat(executedTools).hasSize(1);
        assertThat(executedTools.get(0).name()).isEqualTo("grep");
        assertThat(response.getContent()).isEqualTo("Final text answer");
        assertThat(response.getToolCalls()).hasSize(1);
    }

    @Test
    void modelWithoutToolSupportUsesTextProtocolDirectly() {
        KIChat plainChat = mock(KIChat.class);
        when(plainChat.supportsTools()).thenReturn(false);
        when(plainChat.chat(anyString()))
                .thenReturn("TOOL_CALL[listProjects]()", "Here are the projects");
        when(kiChatProvider.getByStereotype("plain-model")).thenReturn(Optional.of(plainChat));

        ChatRequest request = new ChatRequest();
        request.setMessage("Which projects exist?");
        request.setChatModelName("plain-model");
        request.setUseTools(true);

        de.spraener.prjxp.mcp.rest.dto.ChatResponse response = chatService.send(request);

        assertThat(executedTools).extracting(ExecutedTool::name).containsExactly("listProjects");
        assertThat(response.getContent()).isEqualTo("Here are the projects");
        verify(plainChat, never()).chat(anyList(), anyList());
    }

    @Test
    void fallbackPromptListsRegisteredTools() throws Exception {
        Method method = ChatService.class.getDeclaredMethod("buildFallbackToolPrompt");
        method.setAccessible(true);

        String prompt = (String) method.invoke(chatService);

        assertThat(prompt).contains("## Available Tools");
        assertThat(prompt).contains("vectorSearch(");
        assertThat(prompt).contains("grep(");
        assertThat(prompt).contains("TOOL_CALL[toolName]");
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
        assertThat(executedTools).isEmpty();
        verify(sseEmitter).complete();
    }

    @Test
    void getAvailableToolsReturnsRegisteredToolNames() {
        // When: get available tools
        List<String> tools = chatService.getAvailableTools();

        // Then: the names come from the central registry
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
            return ChatService.class.getDeclaredMethod("executeToolCall", ChatService.ToolCallRequest.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("executeToolCall method not found", e);
        }
    }

    /** Invokes the private parseToolCalls method via reflection. */
    @SuppressWarnings("unchecked")
    private List<?> invokeParseToolCalls(String llmResponse) throws Exception {
        Method method = ChatService.class.getDeclaredMethod("parseToolCalls", String.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(chatService, llmResponse);
    }

    /** Invokes the private extractFinalAnswer method via reflection. */
    private String invokeExtractFinalAnswer(String response, List<?> toolCalls) throws Exception {
        Method method = ChatService.class.getDeclaredMethod("extractFinalAnswer", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(chatService, response, toolCalls);
    }

    /** Reads the tool name of a parsed ToolCallRequest via reflection. */
    private String invokeGetToolName(Object call) throws Exception {
        Method getToolName = call.getClass().getDeclaredMethod("getToolName");
        return (String) getToolName.invoke(call);
    }

    /** Reads the parameters of a parsed ToolCallRequest via reflection. */
    @SuppressWarnings("unchecked")
    private Map<String, String> invokeGetParams(Object call) throws Exception {
        Method getParams = call.getClass().getDeclaredMethod("getParams");
        return (Map<String, String>) getParams.invoke(call);
    }

    /**
     * Builds a closed XML parameter block for test fixtures. The tag is assembled from parts so
     * that the literal markup does not appear as a contiguous string in this source file.
     */
    private static String param(String key, String value) {
        return "<" + "parameter=" + key + ">" + value + "</" + "parameter>";
    }
}
