package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.rest.dto.ChatRequest;
import de.spraener.prjxp.mcp.rest.dto.ChatResponse;
import de.spraener.prjxp.mcp.rest.dto.RagContext;
import de.spraener.prjxp.mcp.rest.dto.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;

import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Matches the head of a tool call: TOOL_CALL[tool]( - the argument list is scanned manually. */
    private static final Pattern TOOL_CALL_HEAD = Pattern.compile("TOOL_CALL\\s*\\[\\s*([A-Za-z0-9_]+)\\s*]\\s*\\(");

    // System prompt. Tool schemas are NOT described here: with native function calling they are
    // negotiated through the provider API. Only the text fallback appends a tool description block.
    private static final String SYSTEM_PROMPT = """
            You are an intelligent assistant with access to the prjxp knowledge management system.

            prjxp indexes the source code and documents of whole projects and lets you search them.

            ## Recommended Search Strategy (3-Stage)
            1. **vectorSearch**: Get semantic overview of relevant code/documentation
            2. **grep**: Find exact definitions, method names, and specific patterns
            3. **readFile** or **readBySignature**: Read the actual source code for detailed understanding

            ## Available Projects
            {project_list}

            ## Important Rules
            - Always use vectorSearch first for broad semantic searches
            - Use grep when you need exact text matching or specific method/class names
            - Use readFile for complete file views, readBySignature for specific methods/classes
            - Always pass the project name (projectName for vectorSearch, project for grep/readFile/readBySignature).
              Use listProjects if you do not know the valid project names.
            - If a tool returns no results, try alternative search terms or approaches
            - Always cite the source files you reference in your answers
            """;

    /**
     * Appended to the prompt only when the model cannot do native function calling.
     * The concrete tool list is injected dynamically from the {@link ToolRegistry}.
     */
    private static final String FALLBACK_TOOL_PROTOCOL = """

            ## Tool Call Format
            When you need to use a tool, include it in your response using this format:
            TOOL_CALL[toolName](param1="value", param2="value")

            STRICT SYNTAX RULES (violating them makes the call fail):
            - ALWAYS wrap every string value in double quotes, even single words.
            - Values may contain brackets, parentheses, commas and spaces - this is fine as long as they are quoted.
              Example: TOOL_CALL[grep](query="public MClass createMClass(String name)", project="cgV19")
            - Escape a double quote inside a value as \\" .
            - Write each TOOL_CALL on its own line. Do NOT wrap it in markdown code fences or backticks.
            - Numbers and booleans are written without quotes (limit=20, skeletonsOnly=false).

            You can include multiple tool calls in one response. After receiving the results, incorporate them into your final answer.
            """;

    private final KIChatProvider kiChatProvider;
    private final EmbeddingModel embeddingModel;
    private final LuceneEmbeddingStore luceneStore;
    private final PrjXPConfig prjXPConfig;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private HubProjectRegistry hubProjectRegistry;

    /** Central tool registry: prjxp tools, external MCP tools and Groovy tools. */
    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    public ChatService(KIChatProvider kiChatProvider,
                       EmbeddingModel embeddingModel,
                       LuceneEmbeddingStore luceneStore,
                       PrjXPConfig prjXPConfig,
                       ObjectMapper objectMapper) {
        this.kiChatProvider = kiChatProvider;
        this.embeddingModel = embeddingModel;
        this.luceneStore = luceneStore;
        this.prjXPConfig = prjXPConfig;
        this.objectMapper = objectMapper;
    }

    /** Send a chat message and return the assistant response. */
    public ChatResponse send(ChatRequest request) {
        // 1. Resolve the LLM by name (chatModelName = stereoType)
        Optional<KIChat> chatOpt = kiChatProvider.getByStereotype(request.getChatModelName());
        if (chatOpt.isEmpty()) {
            throw new IllegalArgumentException("Chat model '" + request.getChatModelName() + "' not found");
        }
        KIChat chat = chatOpt.get();

        // 2. Build the enriched prompt (with system context + RAG if projectContext is provided)
        String enrichedPrompt = buildEnrichedPrompt(request);

        ChatResponse response = new ChatResponse();
        response.setRole("assistant");

        // 3. Run the conversation — with tools (native, text protocol as fallback) or plain
        try {
            if (request.isUseTools()) {
                AgenticLoopResult loopResult = chatWithTools(chat, enrichedPrompt,
                        request.getProjectContext(), null);
                response.setContent(loopResult.finalAnswer);
                if (!loopResult.toolCalls.isEmpty()) {
                    response.setToolCalls(loopResult.toolCalls);
                }
            } else {
                response.setContent(chat.chat(enrichedPrompt));
            }
        } catch (Exception e) {
            log.error("LLM chat failed for model '{}': {}", request.getChatModelName(), e.getMessage());
            throw new RuntimeException("LLM error: " + e.getMessage(), e);
        }

        // 4. Add RAG context if projectContext was used
        if (request.getProjectContext() != null && !request.getProjectContext().isBlank()) {
            response.setRagContext(buildRagContext(request));
        }

        return response;
    }

    /** Send a chat message with SSE streaming for live phase feedback. */
    public void sendStreaming(ChatRequest request, SseEmitter emitter) {
        try {
            // Phase 1: RAG search (if project context provided)
            List<RagContext> ragContexts = new ArrayList<>();
            if (request.getProjectContext() != null && !request.getProjectContext().isBlank()) {
                sendPhase(emitter, "rag_search", "RAG-Suche laeuft...");
                ragContexts = buildRagContext(request);
            }

            // Phase 2: LLM call (with agentic tool loop)
            sendPhase(emitter, "llm_generating", "LLM generiert Antwort...");

            Optional<KIChat> chatOpt = kiChatProvider.getByStereotype(request.getChatModelName());
            if (chatOpt.isEmpty()) {
                throw new IllegalArgumentException("Chat model '" + request.getChatModelName() + "' not found");
            }
            KIChat chat = chatOpt.get();

            String enrichedPrompt = buildEnrichedPrompt(request);

            // Tool loop (native function calling, text protocol as fallback) or plain answer
            String answer;
            List<ToolCall> allToolCalls = new ArrayList<>();
            if (request.isUseTools()) {
                ToolCallListener listener = new ToolCallListener() {
                    @Override
                    public void onToolCallsRequested(List<PendingCall> calls) throws java.io.IOException {
                        sendToolPhase(emitter, calls);
                    }

                    @Override
                    public void onToolResult(String toolName, String result) throws java.io.IOException {
                        sendToolResult(emitter, toolName, result);
                    }
                };
                AgenticLoopResult loopResult = chatWithTools(chat, enrichedPrompt,
                        request.getProjectContext(), listener);
                answer = loopResult.finalAnswer;
                allToolCalls = loopResult.toolCalls;
            } else {
                answer = chat.chat(enrichedPrompt);
            }

            // Phase 4: Complete response
            ChatResponse response = new ChatResponse();
            response.setRole("assistant");
            response.setContent(answer);
            if (!ragContexts.isEmpty()) {
                response.setRagContext(ragContexts);
            }
            if (!allToolCalls.isEmpty()) {
                response.setToolCalls(allToolCalls);
            }

            String json = objectMapper.writeValueAsString(response);
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(json));

            emitter.complete();
        } catch (Exception e) {
            log.error("SSE streaming error: {}", e.getMessage(), e);
            try {
                String errorJson = objectMapper.writeValueAsString(
                        new HashMap<String, String>() {{
                            put("message", e.getMessage());
                        }});
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(errorJson));
                emitter.completeWithError(e);
            } catch (Exception ex) {
                // Emitter already closed, nothing we can do
            }
        }
    }

    // ==================== Agentic Loop & Tool Execution ====================

    private static final int MAX_TOOL_ITERATIONS = 3;
    private static final int TOOL_RESULT_FEEDBACK_CAP = 4000;

    /**
     * Whether native function calling should be attempted: the registry must hold tools and the
     * model must not have tools explicitly disabled via configuration.
     */
    private boolean useNativeTools(KIChat chat) {
        return toolRegistry != null && !toolRegistry.getTools().isEmpty() && chat.supportsTools();
    }

    /**
     * Runs the conversation with tools. Native function calling is attempted first; if the model
     * does not support it (config flag) or the provider rejects the tool request at runtime, the
     * text-based TOOL_CALL protocol is used instead.
     */
    private AgenticLoopResult chatWithTools(KIChat chat, String basePrompt, String defaultProject,
                                            ToolCallListener listener) {
        if (useNativeTools(chat)) {
            try {
                return runNativeToolLoop(chat, basePrompt, defaultProject, listener);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                log.warn("Native tool calling failed ({}), falling back to the text protocol",
                        e.getMessage());
            }
        }
        String prompt = basePrompt + buildFallbackToolPrompt();
        String initialResponse = chat.chat(prompt);
        return runTextProtocolLoop(chat, initialResponse, defaultProject, listener);
    }

    /** Builds the tool description block for the text fallback from the registry. */
    private String buildFallbackToolPrompt() {
        StringBuilder sb = new StringBuilder("\n\n## Available Tools\n");
        if (toolRegistry != null) {
            for (String description : toolRegistry.getToolDescriptions()) {
                sb.append("\n").append(description).append("\n");
            }
        }
        sb.append(FALLBACK_TOOL_PROTOCOL);
        return sb.toString();
    }

    /**
     * Runs the tool loop using native function calling: tool schemas are passed to the provider and
     * the model answers with structured tool execution requests.
     */
    private AgenticLoopResult runNativeToolLoop(KIChat chat, String prompt, String defaultProject,
                                                ToolCallListener listener) throws java.io.IOException {
        List<ToolCall> allToolCalls = new ArrayList<>();
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(prompt));
        List<ToolSpecification> specifications = toolRegistry.specifications();

        String finalAnswer = "";
        boolean finalAnswerPending = false;
        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            dev.langchain4j.model.chat.response.ChatResponse response = chat.chat(messages, specifications);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                finalAnswer = aiMessage.text();
            }

            if (!aiMessage.hasToolExecutionRequests()) {
                finalAnswerPending = false;
                break;
            }
            finalAnswerPending = true;

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (listener != null) {
                listener.onToolCallsRequested(requests.stream()
                        .map(r -> new PendingCall(r.name(), r.arguments()))
                        .toList());
            }

            for (ToolExecutionRequest request : requests) {
                Map<String, Object> arguments = parseToolArguments(request.arguments());
                applyProjectContext(arguments, defaultProject);
                String result = executeRegistryTool(request.name(), arguments);

                ToolCall tc = new ToolCall();
                tc.setToolName(request.name());
                tc.setArguments(arguments);
                tc.setResult(result);
                allToolCalls.add(tc);

                if (listener != null) {
                    listener.onToolResult(request.name(), result);
                }
                messages.add(ToolExecutionResultMessage.from(request,
                        truncate(result, TOOL_RESULT_FEEDBACK_CAP)));
            }
        }

        // The iteration limit counts tool rounds, not the final answer round. Without this
        // extra request, a model that uses the last allowed round returns only a tool call and
        // the UI receives an empty assistant message after the tool marker is removed.
        if (finalAnswerPending) {
            dev.langchain4j.model.chat.response.ChatResponse response = chat.chat(messages, List.of());
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                finalAnswer = aiMessage.text();
            }
        }
        return new AgenticLoopResult(finalAnswer, allToolCalls);
    }

    /** Parses the JSON argument object of a tool call into a plain map. */
    private Map<String, Object> parseToolArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(argumentsJson, Map.class);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            log.warn("Could not parse tool arguments '{}': {}", argumentsJson, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Fills in the chat's project context when the model did not name a project (or used the
     * literal "default", which usually is not a real project).
     */
    private void applyProjectContext(Map<String, Object> arguments, String defaultProject) {
        if (defaultProject == null || defaultProject.isBlank()) {
            return;
        }
        boolean hasProject = false;
        for (String key : new String[]{"project", "projectName", "prj"}) {
            Object value = arguments.get(key);
            String text = value == null ? null : String.valueOf(value).trim();
            if (text != null && !text.isEmpty() && !"default".equalsIgnoreCase(text) && !"null".equalsIgnoreCase(text)) {
                hasProject = true;
            } else if (text != null) {
                arguments.remove(key);
            }
        }
        if (!hasProject) {
            // vectorSearch uses projectName, all other tools use project — set both, unknown keys are ignored
            arguments.put("project", defaultProject);
            arguments.put("projectName", defaultProject);
        }
    }

    /** Executes a tool through the central registry and converts failures into readable text. */
    private String executeRegistryTool(String toolName, Map<String, Object> arguments) {
        if (toolRegistry == null) {
            return "ERROR: tool backend not available. Tools cannot be executed.";
        }
        log.info("executing tool '{}' with arguments {}", toolName, arguments);
        try {
            return toolRegistry.execute(toolName, arguments);
        } catch (IllegalArgumentException e) {
            return "Unknown tool: " + toolName + ". Available tools: " + String.join(", ", getAvailableTools());
        } catch (Exception e) {
            log.error("Tool execution failed for '{}': {}", toolName, e.getMessage());
            return "ERROR executing " + toolName + ": " + e.getMessage();
        }
    }

    /** Receives tool activity so that the SSE path can stream live phase updates. */
    private interface ToolCallListener {
        void onToolCallsRequested(List<PendingCall> calls) throws java.io.IOException;
        void onToolResult(String toolName, String result) throws java.io.IOException;
    }

    /** A tool call the model asked for, before execution. */
    private record PendingCall(String name, String arguments) {}

    /** Run the text-protocol fallback loop: LLM -> TOOL_CALL markers -> execute -> feed back. */
    private AgenticLoopResult runTextProtocolLoop(KIChat chat, String initialResponse,
                                                  String defaultProject, ToolCallListener listener) {
        List<ToolCall> allToolCalls = new ArrayList<>();
        String currentResponse = initialResponse;

        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            List<ToolCallRequest> toolCalls = parseToolCalls(currentResponse);

            if (toolCalls.isEmpty()) {
                // No tool calls - this is the final answer
                break;
            }

            try {
                if (listener != null) {
                    listener.onToolCallsRequested(toolCalls.stream()
                            .map(c -> new PendingCall(c.getToolName(), c.getParams().toString()))
                            .toList());
                }

                // Execute each tool call and collect results
                StringBuilder toolResults = new StringBuilder();
                for (ToolCallRequest call : toolCalls) {
                    String result = executeToolCall(call, defaultProject);
                    ToolCall tc = new ToolCall();
                    tc.setToolName(call.getToolName());
                    tc.setArguments(call.getParamsAsMap());
                    tc.setResult(result);
                    allToolCalls.add(tc);

                    if (listener != null) {
                        listener.onToolResult(call.getToolName(), result);
                    }

                    toolResults.append("### Result from ")
                              .append(call.getToolName())
                              .append(":\n")
                              .append(truncate(result, TOOL_RESULT_FEEDBACK_CAP))
                              .append("\n\n");
                }

                // Feed results back to LLM for next iteration
                String followUpPrompt = currentResponse + "\n\n" +
                    "Tool execution results:\n" + toolResults +
                    "\nBased on these results, provide your final answer. " +
                    "If you need more information, use additional tool calls.";

                currentResponse = chat.chat(followUpPrompt);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        // Strip tool call markers from final answer for clean output
        String finalAnswer = extractFinalAnswer(currentResponse, allToolCalls);
        return new AgenticLoopResult(finalAnswer, allToolCalls);
    }

    /** Extract the final answer from the agentic loop response (strip out tool call markers). */
    private String extractFinalAnswer(String response, List<ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            return response;
        }
        // Remove TOOL_CALL[...](...) markers (quote/paren aware) from the response to get clean text
        StringBuilder sb = new StringBuilder(response);
        List<ToolCallSpan> spans = scanToolCalls(response);
        for (int i = spans.size() - 1; i >= 0; i--) {
            ToolCallSpan span = spans.get(i);
            sb.delete(span.start, span.end);
        }
        return sb.toString().trim();
    }

    /** Parse TOOL_CALL[toolName](params) markers from LLM response. */
    private List<ToolCallRequest> parseToolCalls(String llmResponse) {
        List<ToolCallRequest> calls = new ArrayList<>();
        for (ToolCallSpan span : scanToolCalls(llmResponse)) {
            calls.add(new ToolCallRequest(span.toolName, parseParams(span.paramsStr)));
        }
        return calls;
    }

    /**
     * Scans TOOL_CALL[tool](params) markers. Unlike a plain regex this walks the argument list
     * character by character, so quotes and nested parentheses inside parameter values
     * (e.g. a grep query containing a method signature) do not terminate the call prematurely.
     */
    private List<ToolCallSpan> scanToolCalls(String text) {
        List<ToolCallSpan> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return spans;
        }
        Matcher head = TOOL_CALL_HEAD.matcher(text);
        int searchFrom = 0;
        while (searchFrom <= text.length() && head.find(searchFrom)) {
            String toolName = head.group(1).trim();
            int openParen = head.end() - 1; // position of '('
            int close = findMatchingParen(text, openParen);
            if (close < 0) {
                // Unbalanced parentheses - treat the rest of the line as the argument list (best effort)
                int eol = text.indexOf('\n', openParen);
                int end = (eol < 0) ? text.length() : eol;
                spans.add(new ToolCallSpan(toolName, text.substring(openParen + 1, end), head.start(), end));
                searchFrom = end;
            } else {
                spans.add(new ToolCallSpan(toolName, text.substring(openParen + 1, close), head.start(), close + 1));
                searchFrom = close + 1;
            }
        }
        return spans;
    }

    /** Finds the index of the ')' matching the '(' at openIndex, ignoring parentheses inside quoted strings. */
    private int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '\\') {
                    i++; // skip escaped character
                } else if (c == quoteChar) {
                    inQuotes = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuotes = true;
                quoteChar = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Parse "param1=value, param2=value" into a map. Handles quoted strings and various value formats. */
    private Map<String, String> parseParams(String paramsStr) {
        Map<String, String> result = new HashMap<>();
        if (paramsStr == null || paramsStr.isBlank()) {
            return result;
        }

        // Split by comma, but respect quoted strings
        List<String> pairs = splitRespectingQuotes(paramsStr, ',');
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;

            int eqIndex = trimmed.indexOf('=');
            if (eqIndex <= 0) continue; // key must be before '='

            String key = trimmed.substring(0, eqIndex).trim();
            String value = trimmed.substring(eqIndex + 1).trim();

            // Remove surrounding quotes if present and unescape the content
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = unescape(value.substring(1, value.length() - 1));
            } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = unescape(value.substring(1, value.length() - 1));
            }

            result.put(key, value);
        }
        return result;
    }

    /** Unescape \" \' and \\ sequences inside a quoted parameter value. */
    private String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == '"' || next == '\'' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Split a string by delimiter, respecting quoted substrings. */
    private List<String> splitRespectingQuotes(String input, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuotes && c == '\\' && i + 1 < input.length()) {
                // keep escape sequences intact for later unescaping
                current.append(c).append(input.charAt(i + 1));
                i++;
                continue;
            }

            if (c == '"' || c == '\'') {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = c;
                } else if (c == quoteChar) {
                    inQuotes = false;
                }
                current.append(c);
            } else if (c == delimiter && !inQuotes) {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Execute a single tool call from the text fallback protocol via the central registry. */
    private String executeToolCall(ToolCallRequest request, String defaultProject) {
        Map<String, Object> arguments = request.getParamsAsMap();
        applyProjectContext(arguments, defaultProject);
        return executeRegistryTool(request.getToolName(), arguments);
    }

    // ==================== Helper Methods ====================


    /** Truncate a string to maxChars, appending "..." if truncated. */
    private String truncate(String text, int maxChars) {
        if (text == null) return "(null)";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [truncated, total " + text.length() + " chars]";
    }

    // ==================== SSE Phase Helpers ====================

    /** Send a phase update event to the SSE emitter. */
    private void sendPhase(SseEmitter emitter, String phase, String message) throws java.io.IOException {
        String json = objectMapper.writeValueAsString(
                new HashMap<String, String>() {{
                    put("phase", phase);
                    put("message", message);
                }});
        emitter.send(SseEmitter.event()
                .name("phase")
                .data(json));
    }

    /** Send tool call phase to SSE. */
    private void sendToolPhase(SseEmitter emitter, List<PendingCall> toolCalls) throws java.io.IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "tools");
        data.put("message", "Executing " + toolCalls.size() + " tool(s)...");
        List<Map<String, String>> calls = new ArrayList<>();
        for (PendingCall tc : toolCalls) {
            Map<String, String> callInfo = new HashMap<>();
            callInfo.put("tool", tc.name());
            callInfo.put("params", tc.arguments());
            calls.add(callInfo);
        }
        data.put("toolCalls", calls);

        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event()
                .name("phase")
                .data(json));
    }

    /** Send individual tool result as SSE event. */
    private void sendToolResult(SseEmitter emitter, String toolName, String result) throws java.io.IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "tool_result");
        data.put("tool", toolName);
        data.put("result", truncate(result, 4000));

        String json = objectMapper.writeValueAsString(data);
        emitter.send(SseEmitter.event()
                .name("phase")
                .data(json));
    }

    // ==================== RAG Methods (unchanged) ====================

    /** Build the prompt enriched with system context and RAG if projectContext is provided. */
    private String buildEnrichedPrompt(ChatRequest request) {
        StringBuilder promptBuilder = new StringBuilder();

        // Add system prompt with available projects
        List<String> projectNames = getAvailableProjects();
        String projectList = projectNames.isEmpty() ? "none" : String.join(", ", projectNames);

        promptBuilder.append(SYSTEM_PROMPT.replace("{project_list}", projectList));

        promptBuilder.append("\n\nAvailable projects: ")
                .append(projectList)
                .append("\n\n");

        // Standing instructions: project prompt first, then model prompt (both optional)
        String projectPrompt = resolveProjectPrompt(request.getProjectContext());
        if (projectPrompt != null) {
            promptBuilder.append("PROJECT INSTRUCTIONS:\n")
                    .append(projectPrompt)
                    .append("\n\n");
        }
        String modelPrompt = resolveModelPrompt(request.getChatModelName());
        if (modelPrompt != null) {
            promptBuilder.append("MODEL INSTRUCTIONS:\n")
                    .append(modelPrompt)
                    .append("\n\n");
        }

        // Add RAG context if projectContext is provided
        if (request.getProjectContext() != null && !request.getProjectContext().isBlank()) {
            try {
                // 1. Embed the user's question
                Embedding queryEmbedding = embeddingModel.embed(request.getMessage()).content();

                // 2. Build search request with project filter
                Filter projectFilter = new IsEqualTo(PxChunk.PXCHUNK_PROJECT, request.getProjectContext());
                EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .filter(projectFilter)
                        .maxResults(8)
                        .minScore(0.0)
                        .build();

                // 3. Search for similar chunks
                EmbeddingSearchResult<TextSegment> result = luceneStore.search(searchRequest);

                if (result.matches().isEmpty()) {
                    log.warn("No chunks found for project '{}', using plain message", request.getProjectContext());
                } else {
                    // 4. Build context from retrieved chunks
                    promptBuilder.append("RETRIEVED CONTEXT FROM PROJECT '")
                            .append(request.getProjectContext())
                            .append("':\n\n");

                    for (int i = 0; i < result.matches().size(); i++) {
                        var match = result.matches().get(i);
                        String text = match.embedded().text();
                        promptBuilder.append("Chunk ").append(i + 1)
                                .append(" (relevance: ")
                                .append(String.format("%.2f", match.score()))
                                .append("):\n")
                                .append(text)
                                .append("\n\n");
                    }
                }
            } catch (Exception e) {
                log.error("RAG enrichment failed for project '{}': {}", request.getProjectContext(), e.getMessage());
                // Continue without RAG context
            }
        }

        // Add conversation history if available
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            promptBuilder.append("=== CONVERSATION HISTORY ===\n");
            for (ChatRequest.ChatMessage msg : request.getMessages()) {
                promptBuilder.append(msg.getRole().toUpperCase())
                        .append(": ")
                        .append(msg.getContent())
                        .append("\n");
            }
            promptBuilder.append("===========================\n\n");
        }

        // Add the user's question
        promptBuilder.append("USER QUESTION: ")
                .append(request.getMessage())
                .append("\n\nASSISTANT ANSWER:");

        return promptBuilder.toString();
    }

    /** Resolves the project's standing instructions (hub registry first, config fallback); null when absent. */
    private String resolveProjectPrompt(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return null;
        }
        ProjectDefinition def = null;
        if (hubProjectRegistry != null) {
            def = hubProjectRegistry.entry(projectName).map(ProjectEntry::getDefinition).orElse(null);
        }
        if (def == null) {
            def = prjXPConfig.getProjectDefinition(projectName).orElse(null);
        }
        if (def == null || def.getPrompt() == null) {
            return null;
        }
        String resolved = EnvPlaceholderResolver.resolve(def.getPrompt());
        return (resolved == null || resolved.isBlank()) ? null : resolved;
    }

    /** Resolves the chat model's standing instructions from config (stereoType compared after env resolution); null when absent. */
    private String resolveModelPrompt(String chatModelName) {
        if (chatModelName == null || prjXPConfig.getChatModels() == null) {
            return null;
        }
        for (PrjXPChatModelReference model : prjXPConfig.getChatModels()) {
            if (chatModelName.equals(EnvPlaceholderResolver.resolve(model.getStereoType()))) {
                String resolved = EnvPlaceholderResolver.resolve(model.getPrompt());
                return (resolved == null || resolved.isBlank()) ? null : resolved;
            }
        }
        return null;
    }

    /** Build RAG context entries from the project's chunks. */
    private List<RagContext> buildRagContext(ChatRequest request) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(request.getMessage()).content();

            Filter projectFilter = new IsEqualTo(PxChunk.PXCHUNK_PROJECT, request.getProjectContext());
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .filter(projectFilter)
                    .maxResults(8)
                    .minScore(0.0)
                    .build();

            EmbeddingSearchResult<TextSegment> result = luceneStore.search(searchRequest);

            List<RagContext> contexts = new ArrayList<>();
            for (var match : result.matches()) {
                RagContext ctx = new RagContext();
                ctx.setProjectName(request.getProjectContext());
                String chunkId = match.embedded().metadata().getString("chunkId");
                ctx.setChunkId(chunkId != null ? chunkId : "unknown");
                String text = match.embedded().text();
                ctx.setSnippet(text.substring(0, Math.min(200, text.length())));
                ctx.setScore(match.score());
                contexts.add(ctx);
            }
            return contexts;
        } catch (Exception e) {
            log.error("Failed to build RAG context: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Get list of available project names for RAG context. */
    public List<String> getAvailableProjects() {
        // Prefer hub projects if HubProjectRegistry is available (hub mode)
        if (hubProjectRegistry != null) {
            return hubProjectRegistry.availableProjects();
        }
        // Fallback to PrjXPConfig projects (null-safe)
        if (prjXPConfig.getProjects() == null || prjXPConfig.getProjects().isEmpty()) {
            return List.of();
        }
        return prjXPConfig.getProjects().stream()
                .map(p -> p.getName())
                .toList();
    }

    /** Get the names of all tools available to the chat (prjxp, external MCP servers and Groovy). */
    public List<String> getAvailableTools() {
        if (toolRegistry == null || toolRegistry.getTools().isEmpty()) {
            return List.of("vectorSearch", "grep", "readFile", "readBySignature", "listProjects");
        }
        return toolRegistry.getTools().stream()
                .map(ToolDefinition::getName)
                .toList();
    }

    // ==================== Inner Classes ====================

    /** Result of the agentic loop: final answer text and all tool calls executed. */
    public static class AgenticLoopResult {
        final String finalAnswer;
        final List<ToolCall> toolCalls;

        AgenticLoopResult(String finalAnswer, List<ToolCall> toolCalls) {
            this.finalAnswer = finalAnswer;
            this.toolCalls = toolCalls;
        }
    }

    /** A TOOL_CALL marker found in an LLM response, including its position in the text. */
    private static class ToolCallSpan {
        final String toolName;
        final String paramsStr;
        final int start;
        final int end;

        ToolCallSpan(String toolName, String paramsStr, int start, int end) {
            this.toolName = toolName;
            this.paramsStr = paramsStr;
            this.start = start;
            this.end = end;
        }
    }

    /** Represents a parsed tool call request from the LLM response. */
    public static class ToolCallRequest {
        private final String toolName;
        private final Map<String, String> params;

        ToolCallRequest(String toolName, Map<String, String> params) {
            this.toolName = toolName;
            this.params = params;
        }

        String getToolName() { return toolName; }
        Map<String, String> getParams() { return params; }

        /** Convert params to Object map for ToolCall DTO. */
        Map<String, Object> getParamsAsMap() {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                result.put(entry.getKey(), tryParseValue(entry.getValue()));
            }
            return result;
        }

        /** Try to parse a string value into its proper type (number, boolean). */
        private Object tryParseValue(String value) {
            if (value == null) return null;
            // Try boolean
            if ("true".equalsIgnoreCase(value)) return true;
            if ("false".equalsIgnoreCase(value)) return false;
            // Try integer
            try { return Integer.parseInt(value); } catch (NumberFormatException e) { /* not int */ }
            // Try double
            try { return Double.parseDouble(value); } catch (NumberFormatException e) { /* not double */ }
            // Return as string
            return value;
        }
    }
}
