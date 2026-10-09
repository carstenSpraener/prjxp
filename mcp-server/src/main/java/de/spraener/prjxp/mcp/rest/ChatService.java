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

import de.spraener.prjxp.mcp.PrjxpMcpTool;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.SymbolReadResult;

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

    // System prompt that tells the LLM what tools are available (real prjxp MCP tools)
    private static final String SYSTEM_PROMPT = """
            You are an intelligent assistant with access to the prjxp knowledge management system.

            ## Available Tools (5 total)

            ### 1. vectorSearch
            CRITICAL PRIMARY SEARCH TOOL (level 1 of 3). Delivers relevant semantic information from the project context.
            Use for finding classes, understanding behavior, locating implementations, or exploring the codebase.

            Parameters:
            - userQuestion (required): A targeted search prompt for vector retrieval
            - projectName (optional): Project name to narrow scope
            - similarity (optional, 0.0-1.0): Similarity threshold (default 0.85)
            - maxResults (optional, default 20): Max results to return
            - skeletonsOnly (optional, boolean): Return class skeletons only vs full method context

            ### 2. grep
            EXACT FULL-TEXT SEARCH (level 2 of 3). Searches all chunks for exact text patterns.
            Use after vectorSearch to find specific code, symbols, or text.

            Parameters:
            - query (required): Exact search string
            - project (optional): Project name filter
            - language (optional): Language filter ('java', 'ts', etc.)
            - limit (optional, default 10, max 100): Max results

            ### 3. readFile
            READER (level 3 of 3). Returns COMPLETE source view of one file from the project index.

            Parameters:
            - file (required): File path (absolute, relative, or just filename)
            - project (optional): Project name
            - offset (optional): 1-based line number to start from
            - limit (optional): Max lines to return

            ### 4. readBySignature
            READER (level 3 of 3). Returns COMPLETE implementation of a symbol including javadoc and full method body.

            Parameters:
            - method (required): Method name or fully qualified 'com.example.Foo#bar'. Class FQNs return class frame.
            - container (optional): Containing class FQN to disambiguate bare method names
            - project (optional): Project name

            ### 5. listProjects
            DISCOVERY TOOL: Lists all project names with searchable indexes.

            Parameters: None

            ## Recommended Search Strategy (3-Stage)
            1. **vectorSearch**: Get semantic overview of relevant code/documentation
            2. **grep**: Find exact definitions, method names, and specific patterns
            3. **readFile** or **readBySignature**: Read the actual source code for detailed understanding

            ## Tool Call Format
            When you need to use a tool, include it in your response using this format:
            TOOL_CALL[toolName](param1=value, param2=value)

            Example:
            TOOL_CALL[vectorSearch](userQuestion="CodeTarget class and its methods", projectName="cgV19")

            You can include multiple tool calls in one response. After receiving the results, incorporate them into your final answer.

            ## Available Projects
            {project_list} - will be populated dynamically based on the user's context.

            ## Important Rules
            - Always use vectorSearch first for broad semantic searches
            - Use grep when you need exact text matching or specific method/class names
            - Use readFile for complete file views, readBySignature for specific methods/classes
            - If a tool returns no results, try alternative search terms or approaches
            - Always cite the source files you reference in your answers
            """;

    private final KIChatProvider kiChatProvider;
    private final EmbeddingModel embeddingModel;
    private final LuceneEmbeddingStore luceneStore;
    private final PrjXPConfig prjXPConfig;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private HubProjectRegistry hubProjectRegistry;

    // Inject the real MCP tool bean for execution
    @Autowired(required = false)
    private PrjxpMcpTool mcpTool;

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

        // 3. Send to LLM (with error handling)
        String content;
        try {
            content = chat.chat(enrichedPrompt);
        } catch (Exception e) {
            log.error("LLM chat failed for model '{}': {}", request.getChatModelName(), e.getMessage());
            throw new RuntimeException("LLM error: " + e.getMessage(), e);
        }

        // 4. Build response
        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        response.setContent(content);

        // 5. Add RAG context if projectContext was used
        if (request.getProjectContext() != null && !request.getProjectContext().isBlank()) {
            response.setRagContext(buildRagContext(request));
        }

        // 6. Agentic tool loop (if useTools enabled)
        if (request.isUseTools()) {
            AgenticLoopResult loopResult = runAgenticLoopWithFinalAnswer(chat, enrichedPrompt, content);
            if (!loopResult.toolCalls.isEmpty()) {
                // Use the final response from the agentic loop
                response.setContent(loopResult.finalAnswer);
                response.setToolCalls(loopResult.toolCalls);
            }
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
            String currentResponse = chat.chat(enrichedPrompt);

            // Agentic tool loop (if useTools enabled)
            List<ToolCall> allToolCalls = new ArrayList<>();
            if (request.isUseTools()) {
                int maxIterations = 3;
                for (int iter = 0; iter < maxIterations; iter++) {
                    List<ToolCallRequest> toolCalls = parseToolCalls(currentResponse);

                    if (toolCalls.isEmpty()) {
                        // No tool calls - this is the final answer
                        break;
                    }

                    // Send tool call phase to SSE
                    sendToolPhase(emitter, toolCalls);

                    // Execute each tool call and collect results for follow-up prompt
                    StringBuilder toolResults = new StringBuilder();
                    for (ToolCallRequest call : toolCalls) {
                        String result = executeToolCall(call);
                        ToolCall tc = new ToolCall();
                        tc.setToolName(call.getToolName());
                        tc.setArguments(call.getParamsAsMap());
                        tc.setResult(result);
                        allToolCalls.add(tc);

                        // Send individual tool result as SSE event
                        sendToolResult(emitter, call.getToolName(), result);

                        // Collect for follow-up prompt
                        toolResults.append("### Result from ")
                                  .append(call.getToolName())
                                  .append(":\n")
                                  .append(truncate(result, 4000))
                                  .append("\n\n");
                    }

                    // Feed results back to LLM for next iteration
                    String followUpPrompt = currentResponse + "\n\n" +
                        "Tool execution results:\n" + toolResults +
                        "\nBased on these results, provide your final answer. " +
                        "If you need more information, use additional tool calls.";

                    currentResponse = chat.chat(followUpPrompt);
                }
            }

            // Phase 4: Complete response
            ChatResponse response = new ChatResponse();
            response.setRole("assistant");
            response.setContent(currentResponse);
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

    /** Run the agentic loop: LLM -> tool calls -> execute -> feed back -> final answer. */
    private AgenticLoopResult runAgenticLoopWithFinalAnswer(KIChat chat, String initialPrompt, String initialResponse) {
        List<ToolCall> allToolCalls = new ArrayList<>();
        String currentResponse = initialResponse;
        int maxIterations = 3;

        for (int iter = 0; iter < maxIterations; iter++) {
            List<ToolCallRequest> toolCalls = parseToolCalls(currentResponse);

            if (toolCalls.isEmpty()) {
                // No tool calls - this is the final answer
                break;
            }

            // Execute each tool call and collect results
            StringBuilder toolResults = new StringBuilder();
            for (ToolCallRequest call : toolCalls) {
                String result = executeToolCall(call);
                ToolCall tc = new ToolCall();
                tc.setToolName(call.getToolName());
                tc.setArguments(call.getParamsAsMap());
                tc.setResult(result);
                allToolCalls.add(tc);

                toolResults.append("### Result from ")
                          .append(call.getToolName())
                          .append(":\n")
                          .append(truncate(result, 4000))
                          .append("\n\n");
            }

            // Feed results back to LLM for next iteration
            String followUpPrompt = currentResponse + "\n\n" +
                "Tool execution results:\n" + toolResults +
                "\nBased on these results, provide your final answer. " +
                "If you need more information, use additional tool calls.";

            currentResponse = chat.chat(followUpPrompt);
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
        // Remove TOOL_CALL[...](...) markers from the response to get clean text
        String cleaned = Pattern.compile("TOOL_CALL\\[[^\\]]+\\]\\([^)]*\\)").matcher(response).replaceAll("");
        return cleaned.trim();
    }

    /** Parse TOOL_CALL[toolName](params) markers from LLM response. */
    private List<ToolCallRequest> parseToolCalls(String llmResponse) {
        List<ToolCallRequest> calls = new ArrayList<>();
        Pattern pattern = Pattern.compile("TOOL_CALL\\[([^\\]]+)\\]\\(([^)]*)\\)");
        Matcher matcher = pattern.matcher(llmResponse);
        while (matcher.find()) {
            String toolName = matcher.group(1).trim();
            String paramsStr = matcher.group(2);
            Map<String, String> params = parseParams(paramsStr);
            calls.add(new ToolCallRequest(toolName, params));
        }
        return calls;
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

            // Remove surrounding quotes if present
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }

            result.put(key, value);
        }
        return result;
    }

    /** Split a string by delimiter, respecting quoted substrings. */
    private List<String> splitRespectingQuotes(String input, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if ((c == '"' || c == '\'') && (current.length() == 0 || input.charAt(i - 1) != '\\')) {
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

    /** Execute a single tool call and return the result as a string. */
    private String executeToolCall(ToolCallRequest request) {
        if (mcpTool == null) {
            return "ERROR: MCP tool backend not available. Tools cannot be executed.";
        }

        try {
            switch (request.getToolName()) {
                case "vectorSearch":
                    return executeVectorSearch(request.getParams());
                case "grep":
                    return executeGrep(request.getParams());
                case "readFile":
                    return executeReadFile(request.getParams());
                case "readBySignature":
                    return executeReadBySignature(request.getParams());
                case "listProjects":
                    List<String> projects = mcpTool.listProjects();
                    return projects.isEmpty() ? "No searchable projects available." : String.join(", ", projects);
                default:
                    return "Unknown tool: " + request.getToolName() + ". Available tools: vectorSearch, grep, readFile, readBySignature, listProjects";
            }
        } catch (Exception e) {
            log.error("Tool execution failed for '{}': {}", request.getToolName(), e.getMessage());
            return "ERROR executing " + request.getToolName() + ": " + e.getMessage();
        }
    }

    private String executeVectorSearch(Map<String, String> params) {
        String question = params.getOrDefault("userQuestion", "");
        if (question.isBlank()) {
            return "ERROR: 'userQuestion' parameter is required for vectorSearch.";
        }

        String project = getParamOrNull(params, "projectName");
        Double similarity = parseDoubleOrNull(params.get("similarity"));
        Integer maxResults = parseIntOrNull(params.get("maxResults"));
        Boolean skeletonsOnly = parseBooleanOrNull(params.get("skeletonsOnly"));

        String result = mcpTool.vectorSearch(question, project, similarity, maxResults, skeletonsOnly);
        return truncate(result, 8000); // Limit result size to avoid token overflow
    }

    private String executeGrep(Map<String, String> params) {
        String query = params.getOrDefault("query", "");
        if (query.isBlank()) {
            return "ERROR: 'query' parameter is required for grep.";
        }

        String project = getParamOrNull(params, "project");
        String language = getParamOrNull(params, "language");
        Integer limit = parseIntOrNull(params.get("limit"));

        List<SearchHit> hits = mcpTool.grep(query, project, language, limit);
        if (hits == null || hits.isEmpty()) {
            return "No results found for grep query: \"" + query + "\"";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(hits.size()).append(" result(s) for grep query: \"").append(query).append("\"\n\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append("Result ").append(i + 1).append(":\n");
            if (hit.file() != null) sb.append("  File: ").append(hit.file()).append("\n");
            if (hit.lineFrom() != null) sb.append("  Lines: ").append(hit.lineFrom()).append("-").append(hit.lineTo() != null ? hit.lineTo() : "?").append("\n");
            if (hit.snippet() != null) sb.append("  Snippet:\n").append(hit.snippet()).append("\n");
            if (hit.chunkId() != null) sb.append("  Chunk: ").append(hit.chunkId()).append("\n");
            sb.append("\n");
        }
        return truncate(sb.toString(), 8000);
    }

    private String executeReadFile(Map<String, String> params) {
        String file = params.getOrDefault("file", "");
        if (file.isBlank()) {
            return "ERROR: 'file' parameter is required for readFile.";
        }

        String project = getParamOrNull(params, "project");
        Integer offset = parseIntOrNull(params.get("offset"));
        Integer limit = parseIntOrNull(params.get("limit"));

        FileView view = mcpTool.readFile(file, project, offset, limit);
        if (view == null) {
            return "No file view returned for: \"" + file + "\"";
        }

        StringBuilder sb = new StringBuilder();
        if (view.error() != null) {
            return "ERROR reading file: " + view.error();
        }
        if (view.candidates() != null && !view.candidates().isEmpty()) {
            sb.append("Ambiguous file path. Candidates:\n");
            for (String cand : view.candidates()) {
                sb.append("  - ").append(cand).append("\n");
            }
            return sb.toString();
        }

        sb.append("File: ").append(view.file() != null ? view.file() : file).append("\n");
        if (view.totalLines() != null) sb.append("Total lines: ").append(view.totalLines()).append("\n");
        if (view.returnedLines() != null) sb.append("Returned lines: ").append(view.returnedLines()).append("\n");
        if (view.truncated()) sb.append("(Content truncated at 100k char cap)\n");
        sb.append("\n--- FILE CONTENT ---\n");
        if (view.content() != null) {
            sb.append(view.content());
        } else {
            sb.append("(No content available)");
        }
        return truncate(sb.toString(), 10000);
    }

    private String executeReadBySignature(Map<String, String> params) {
        String method = params.getOrDefault("method", "");
        if (method.isBlank()) {
            return "ERROR: 'method' parameter is required for readBySignature.";
        }

        String container = getParamOrNull(params, "container");
        String project = getParamOrNull(params, "project");

        SymbolReadResult result = mcpTool.readBySignature(method, container, project);
        if (result == null) {
            return "No result returned for symbol: \"" + method + "\"";
        }

        StringBuilder sb = new StringBuilder();
        if (result.error() != null) {
            return "ERROR reading symbol: " + result.error();
        }

        sb.append("Symbol: ").append(method).append("\n");
        if (result.matches() != null && !result.matches().isEmpty()) {
            sb.append("Found ").append(result.matches().size()).append(" match(es):\n\n");
            for (int i = 0; i < result.matches().size(); i++) {
                var match = result.matches().get(i);
                sb.append("Match ").append(i + 1).append(":\n");
                if (match.fqn() != null) sb.append("  FQN: ").append(match.fqn()).append("\n");
                if (match.file() != null) sb.append("  File: ").append(match.file()).append("\n");
                if (match.lineFrom() != null) sb.append("  Lines: ").append(match.lineFrom()).append("-").append(match.lineTo() != null ? match.lineTo() : "?").append("\n");
                if (match.javadoc() != null) sb.append("  Javadoc:\n").append(match.javadoc()).append("\n");
                if (match.body() != null) sb.append("  Body:\n").append(match.body()).append("\n");
                sb.append("\n");
            }
        } else {
            sb.append("No matches found for symbol: \"").append(method).append("\"");
        }

        if (result.remainingFqns() != null && !result.remainingFqns().isEmpty()) {
            sb.append("\nAdditional matches not shown (call again with one of these FQNs):\n");
            for (String fqn : result.remainingFqns()) {
                sb.append("  - ").append(fqn).append("\n");
            }
        }

        return truncate(sb.toString(), 10000);
    }

    // ==================== Helper Methods ====================

    /** Get a param value or null if not present/blank. */
    private String getParamOrNull(Map<String, String> params, String key) {
        if (params == null || !params.containsKey(key)) return null;
        String val = params.get(key);
        return (val != null && !val.isBlank()) ? val : null;
    }

    /** Parse a string to Double or return null. */
    private Double parseDoubleOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return null; }
    }

    /** Parse a string to Integer or return null. */
    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }

    /** Parse a string to Boolean or return null. */
    private Boolean parseBooleanOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return Boolean.parseBoolean(value);
    }

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
    private void sendToolPhase(SseEmitter emitter, List<ToolCallRequest> toolCalls) throws java.io.IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "tools");
        data.put("message", "Executing " + toolCalls.size() + " tool(s)...");
        List<Map<String, String>> calls = new ArrayList<>();
        for (ToolCallRequest tc : toolCalls) {
            Map<String, String> callInfo = new HashMap<>();
            callInfo.put("tool", tc.getToolName());
            callInfo.put("params", tc.getParams().toString());
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

    /** Get list of available MCP tool names. */
    public List<String> getAvailableTools() {
        return List.of("vectorSearch", "grep", "readFile", "readBySignature", "listProjects");
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
