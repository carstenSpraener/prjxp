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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * Matches an explicit XML-style tool name emitted by some models (e.g. Qwen) that ignore the
     * TOOL_CALL protocol: {@code <function=readFile>}, {@code <tool_name>readFile</tool_name>}.
     * Group 1 = name in attribute form, group 2 = name as element content.
     */
    private static final Pattern XML_TOOL_NAME = Pattern.compile(
            "<(?:function|tool_name|name)\\s*(?:=\\s*[\"']?([A-Za-z0-9_]+)[\"']?)?\\s*>([A-Za-z0-9_]*)</(?:function|tool_name|name)>",
            Pattern.CASE_INSENSITIVE);

    /** Opening tag of an XML parameter element, built from parts to stay out of tooling. */
    private static final String XML_PARAM_OPEN = "<" + "parameter";

    /** Closing tag of an XML parameter element, built from parts to stay out of tooling. */
    private static final String XML_PARAM_CLOSE = "</" + "parameter";

    /**
     * Matches a single XML parameter block in either common form (a name attribute or a
     * tag-name attribute). Group 1 = name attribute, group 2 = tag-name attribute,
     * group 3 = element content.
     */
    private static final Pattern XML_PARAM = Pattern.compile(
            XML_PARAM_OPEN + "\\s*(?:name\\s*=\\s*[\"']([^\"']+)[\"'])?\\s*(?:=\\s*([A-Za-z0-9_]+))?\\s*>(.*?)"
                    + "(?:</" + "parameter\\s*>|(?=<[A-Za-z][A-Za-z0-9_]*)|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches an opening XML name tag in attribute form, e.g. {@code <function=readFile>}. */
    private static final Pattern XML_TOOL_NAME_OPEN = Pattern.compile(
            "<(?:function|tool_name|name)\\s*=\\s*[\"']?([A-Za-z0-9_]+)[\"']?\\s*>",
            Pattern.CASE_INSENSITIVE);

    /** Matches a closing XML name tag, e.g. {@code </function}>. */
    private static final Pattern XML_TOOL_NAME_CLOSE = Pattern.compile(
            "</(?:function|tool_name|name)\\s*>",
            Pattern.CASE_INSENSITIVE);

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

            ## Tool Call Format (CRITICAL)
            When you need to use a tool, include it in your response using this EXACT format:
            TOOL_CALL[toolName](param1="value", param2="value")

            STRICT SYNTAX RULES (violating them makes the call fail):
            - ALWAYS wrap every string value in double quotes, even single words.
            - Values may contain brackets, parentheses, commas and spaces - this is fine as long as they are quoted.
              Example: TOOL_CALL[grep](query="public MClass createMClass(String name)", project="cgV19")
            - Escape a double quote inside a value as \\" .
            - Write each TOOL_CALL on its own line. Do NOT wrap it in markdown code fences or backticks.
            - Numbers and booleans are written without quotes (limit=20, skeletonsOnly=false).

            FORBIDDEN FORMATS - do NOT use any of these for tool calls:
            - XML or HTML tags such as <function=...>, <tool_name>...</tool_name>, <parameter ...>
            - JSON objects, YAML, or any other structured syntax
            These will NOT be recognized and your tool call will be silently dropped.

            You can include multiple tool calls in one response (one TOOL_CALL per line).
            After receiving the results, incorporate them into your final answer.
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
        // Remove tool call markers (text protocol and recovered XML calls) to get clean text
        StringBuilder sb = new StringBuilder(response);
        List<DetectedCall> calls = detectCalls(response);
        for (int i = calls.size() - 1; i >= 0; i--) {
            DetectedCall call = calls.get(i);
            sb.delete(call.start(), Math.min(call.end(), sb.length()));
        }
        return sb.toString().trim();
    }

    /** Parse tool calls from an LLM response: the TOOL_CALL text protocol, with an XML fallback. */
    private List<ToolCallRequest> parseToolCalls(String llmResponse) {
        return detectCalls(llmResponse).stream()
                .map(call -> new ToolCallRequest(call.toolName(), call.params()))
                .toList();
    }

    /**
     * Detects all tool calls in a response. The primary format is the TOOL_CALL[tool](params)
     * text protocol; when no such markers are present, falls back to recognizing the XML-style
     * calls that some models (e.g. Qwen) emit instead of the requested format.
     */
    private List<DetectedCall> detectCalls(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        List<DetectedCall> calls = scanTextProtocolCalls(text);
        if (calls.isEmpty()) {
            calls = scanXmlCalls(text);
        }
        return calls;
    }

    /**
     * Scans TOOL_CALL[tool](params) markers. Unlike a plain regex this walks the argument list
     * character by character, so quotes and nested parentheses inside parameter values
     * (e.g. a grep query containing a method signature) do not terminate the call prematurely.
     */
    private List<DetectedCall> scanTextProtocolCalls(String text) {
        List<DetectedCall> calls = new ArrayList<>();
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
                calls.add(new DetectedCall(toolName, parseParams(text.substring(openParen + 1, end)), head.start(), end));
                searchFrom = end;
            } else {
                calls.add(new DetectedCall(toolName, parseParams(text.substring(openParen + 1, close)), head.start(), close + 1));
                searchFrom = close + 1;
            }
        }
        return calls;
    }

    /**
     * Best-effort recovery of XML-style tool calls: explicit name tags with nested parameter
     * blocks, or bare parameter blocks whose tool is inferred from the keys. Some models ignore
     * the TOOL_CALL protocol and emit XML markup instead (sometimes with unclosed tags). Returns
     * an empty list when nothing plausible is found, so that ordinary answers containing
     * incidental tags are left alone.
     */
    private List<DetectedCall> scanXmlCalls(String text) {
        // Collect all anchors (name tags and parameter blocks), then group them in document order.
        List<XmlAnchor> anchors = new ArrayList<>();

        Matcher openName = XML_TOOL_NAME_OPEN.matcher(text);
        while (openName.find()) {
            String name = openName.group(1);
            if (name != null && !name.isEmpty()) {
                anchors.add(new XmlAnchor(openName.start(), openName.end(), name, Map.of()));
            }
        }

        Matcher selfName = XML_TOOL_NAME.matcher(text);
        while (selfName.find()) {
            String name = firstNonEmpty(selfName.group(1), selfName.group(2));
            if (name != null) {
                anchors.add(new XmlAnchor(selfName.start(), selfName.end(), name, Map.of()));
            }
        }

        Matcher param = XML_PARAM.matcher(text);
        while (param.find()) {
            String key = firstNonEmpty(param.group(1), param.group(2));
            if (key == null) {
                continue;
            }
            String value = param.group(3) == null ? "" : param.group(3).trim();
            anchors.add(new XmlAnchor(param.start(), param.end(), null, Map.of(key, value)));
        }

        if (anchors.isEmpty()) {
            return List.of();
        }

        anchors.sort(Comparator.comparingInt(XmlAnchor::start));

        // A name tag starts a new call; parameter blocks attach to the current (or an inferred) one.
        List<DetectedCall> calls = new ArrayList<>();
        String name = null;
        Map<String, String> params = new LinkedHashMap<>();
        int groupStart = -1;
        int groupEnd = -1;

        for (int i = 0; i <= anchors.size(); i++) {
            boolean newGroup = i < anchors.size() && anchors.get(i).toolName() != null;
            if (newGroup || i == anchors.size()) {
                flushXmlGroup(calls, text, name, params, groupStart, groupEnd);
                if (i == anchors.size()) {
                    break;
                }
                XmlAnchor anchor = anchors.get(i);
                name = anchor.toolName();
                params = new LinkedHashMap<>();
                groupStart = anchor.start();
                groupEnd = anchor.end();
            } else {
                XmlAnchor anchor = anchors.get(i);
                if (groupStart < 0) {
                    groupStart = anchor.start();
                }
                params.putAll(anchor.params());
                groupEnd = Math.max(groupEnd, anchor.end());
            }
        }

        return calls;
    }

    /** Adds the accumulated XML group as a call when it resolves to a plausible tool. */
    private void flushXmlGroup(List<DetectedCall> calls, String text, String name,
                                Map<String, String> params, int groupStart, int groupEnd) {
        if (groupStart < 0) {
            return;
        }
        String resolvedName = name != null ? canonicalToolName(name) : inferToolName(params);
        if (resolvedName == null) {
            if (!params.isEmpty()) {
                log.warn("Detected XML tool-call parameters but could not infer the tool: {}", params.keySet());
            }
            return; // no resolvable tool name - almost certainly prose, not a call
        }
        if (name != null && params.isEmpty() && !isRegisteredTool(resolvedName)) {
            return; // a name tag without parameters that names no registered tool is prose (e.g. <name>John</name>)
        }
        if (name != null) {
            log.info("Recovered XML-style tool call '{}' - the model ignored the TOOL_CALL protocol", resolvedName);
        } else {
            log.info("Recovered XML tool call, inferred tool '{}' from parameter keys {}", resolvedName, params.keySet());
        }
        int end = name != null ? extendToClosingTag(text, groupEnd) : groupEnd;
        calls.add(new DetectedCall(resolvedName, params, groupStart, end));
    }

    /** Maps a tool name to its registered spelling (case-insensitive), or returns it unchanged. */
    private String canonicalToolName(String name) {
        if (toolRegistry != null) {
            for (ToolDefinition tool : toolRegistry.getTools()) {
                if (tool.getName().equalsIgnoreCase(name)) {
                    return tool.getName();
                }
            }
        }
        return name;
    }

    /** Whether a tool with the given name (case-insensitive) is registered. */
    private boolean isRegisteredTool(String name) {
        if (toolRegistry == null || name == null) {
            return false;
        }
        for (ToolDefinition tool : toolRegistry.getTools()) {
            if (tool.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Infers the most likely tool for a set of parameter keys by matching them against the
     * parameters declared on registered tools. Returns null when no confident match exists, so
     * that ambiguous recoveries are not executed blindly.
     */
    private String inferToolName(Map<String, String> params) {
        if (toolRegistry == null || toolRegistry.getTools().isEmpty() || params.isEmpty()) {
            return null;
        }
        String bestName = null;
        int bestScore = 0;
        boolean ambiguous = false;
        for (ToolDefinition tool : toolRegistry.getTools()) {
            int score = 0;
            for (String key : params.keySet()) {
                if (tool.getParameters() != null && tool.getParameters().containsKey(key)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestName = tool.getName();
                bestScore = score;
                ambiguous = false;
            } else if (score == bestScore && score > 0) {
                ambiguous = true; // two tools match equally well - do not guess
            }
        }
        return (bestScore >= 2 || (bestScore == 1 && !ambiguous)) ? bestName : null;
    }

    /** Returns the first non-empty value, or null. */
    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Extends the end of a named XML call to include its closing tag, if one follows nearby. */
    private int extendToClosingTag(String text, int from) {
        Matcher close = XML_TOOL_NAME_CLOSE.matcher(text);
        if (!close.find(from) || close.start() - from > 1000) {
            return from; // no nearby closing tag - keep the current end
        }
        if (XML_TOOL_NAME_OPEN.matcher(text.substring(from, close.start())).find()) {
            return from; // another call starts in between - the closing tag belongs to it
        }
        return close.end();
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

    /** A tool call detected in an LLM response (either protocol), with its span for later stripping. */
    private record DetectedCall(String toolName, Map<String, String> params, int start, int end) {}

    /** A positional anchor in an XML-style tool call: a name tag or a single parameter block. */
    private record XmlAnchor(int start, int end, String toolName, Map<String, String> params) {}

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
