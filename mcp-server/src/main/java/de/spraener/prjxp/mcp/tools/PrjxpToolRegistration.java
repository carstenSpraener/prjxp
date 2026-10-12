package de.spraener.prjxp.mcp.tools;

import de.spraener.prjxp.common.model.FileView;import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registers the five prjxp search tools in the central {@link ToolRegistry} so that they are
 * available for native function calling — executed in-process as plain Java calls on
 * {@link PrjxpMcpTool}, without a JSON-RPC round trip.
 * <p>
 * Registered early (highest precedence) so that external MCP or Groovy tools cannot shadow them:
 * the first registration of a name wins.
 */
@Component
@RequiredArgsConstructor
public class PrjxpToolRegistration {

    private static final Logger log = LoggerFactory.getLogger(PrjxpToolRegistration.class);

    /** Result size caps (characters) to avoid blowing the model's context window. */
    private static final int SEARCH_RESULT_CAP = 8000;
    private static final int READER_RESULT_CAP = 10000;

    private final ToolRegistry toolRegistry;
    private final PrjxpMcpTool mcpTool;

    @PostConstruct
    public void registerTools() {
        register(vectorSearchTool());
        register(grepTool());
        register(readFileTool());
        register(readBySignatureTool());
        register(listProjectsTool());
    }

    private void register(ToolDefinition tool) {
        try {
            toolRegistry.register(tool);
        } catch (RuntimeException e) {
            log.error("Could not register prjxp tool '{}': {}", tool.getName(), e.getMessage());
        }
    }

    // ==================== Tool definitions ====================

    private ToolDefinition vectorSearchTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("vectorSearch")
                .description("""
                        CRITICAL PRIMARY SEARCH TOOL (level 1 of 3): Delivers relevant semantic information from the project context.
                        ALWAYS call this first for discovery: finding classes, understanding structure, locating implementations.
                        Follow up with grep (exact string), readBySignature (complete method) or readFile (whole file).
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("userQuestion",
                                "A targeted, standalone search prompt optimized for vector retrieval.")
                        .addStringProperty("projectName",
                                "Optional project name to narrow the scope.")
                        .addNumberProperty("similarity",
                                "Similarity threshold 0.0-1.0. Higher is more precise. Default 0.85.")
                        .addIntegerProperty("maxResults",
                                "Maximum number of results to return. Default 20.")
                        .addBooleanProperty("skeletonsOnly",
                                "True returns class skeletons only, false (default) returns full method context.")
                        .required("userQuestion")
                        .build())
                .build();

        return new ToolDefinition("vectorSearch", spec.description(),
                Map.of("userQuestion", "The search prompt", "projectName", "Optional project name"),
                params -> {
                    String question = string(params, "userQuestion");
                    if (question == null) {
                        return "ERROR: 'userQuestion' parameter is required for vectorSearch.";
                    }
                    String result = mcpTool.vectorSearch(question, string(params, "projectName"),
                            number(params, "similarity"), integer(params, "maxResults"),
                            bool(params, "skeletonsOnly"));
                    return truncate(result, SEARCH_RESULT_CAP);
                }, spec);
    }

    private ToolDefinition grepTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grep")
                .description("""
                        SEARCH TOOL (level 2 of 3): Exact full-text search over all chunks of a project.
                        Use the exact method signature as query to retrieve its FULL BODY.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "Exact search string, e.g. 'public MClass createMClass'.")
                        .addStringProperty("project", "Optional project name.")
                        .addStringProperty("language", "Optional language filter, e.g. 'java' or 'ts'.")
                        .addIntegerProperty("limit", "Maximum number of results (default 10, max 100).")
                        .required("query")
                        .build())
                .build();

        return new ToolDefinition("grep", spec.description(),
                Map.of("query", "Exact search string", "project", "Optional project name"),
                params -> {
                    String query = string(params, "query");
                    if (query == null) {
                        return "ERROR: 'query' parameter is required for grep.";
                    }
                    String project = string(params, "project");
                    List<SearchHit> hits = mcpTool.grep(query, project, string(params, "language"),
                            integer(params, "limit"));
                    return formatHits(query, project, hits);
                }, spec);
    }

    private ToolDefinition readFileTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("readFile")
                .description("""
                        READER (level 3 of 3): Returns a COMPLETE source view of one file, reconstructed from the project index.
                        Use when you need the entire file instead of snippets or skeletons.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file",
                                "File path — absolute, project-root-relative, or just the file name.")
                        .addStringProperty("project", "Optional project name.")
                        .addIntegerProperty("offset", "Optional 1-based line number to start reading from.")
                        .addIntegerProperty("limit", "Optional maximum number of lines to return.")
                        .required("file")
                        .build())
                .build();

        return new ToolDefinition("readFile", spec.description(),
                Map.of("file", "File path", "project", "Optional project name"),
                params -> {
                    String file = string(params, "file");
                    if (file == null) {
                        return "ERROR: 'file' parameter is required for readFile.";
                    }
                    FileView view = mcpTool.readFile(file, string(params, "project"),
                            integer(params, "offset"), integer(params, "limit"));
                    return formatFileView(file, view);
                }, spec);
    }

    private ToolDefinition readBySignatureTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("readBySignature")
                .description("""
                        READER (level 3 of 3): Returns the COMPLETE implementation of a symbol — javadoc + full method body.
                        Java only. Class FQNs return the class frame.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("method",
                                "Method name (e.g. 'createMClass') or fully qualified 'com.example.Foo#bar'.")
                        .addStringProperty("container",
                                "Optional containing class FQN to disambiguate a bare method name.")
                        .addStringProperty("project", "Optional project name.")
                        .required("method")
                        .build())
                .build();

        return new ToolDefinition("readBySignature", spec.description(),
                Map.of("method", "Method name or FQN", "project", "Optional project name"),
                params -> {
                    String method = string(params, "method");
                    if (method == null) {
                        return "ERROR: 'method' parameter is required for readBySignature.";
                    }
                    SymbolReadResult result = mcpTool.readBySignature(method,
                            string(params, "container"), string(params, "project"));
                    return formatSymbolResult(method, result);
                }, spec);
    }

    private ToolDefinition listProjectsTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("listProjects")
                .description("DISCOVERY TOOL: Lists all project names that have a searchable index in this server.")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        return new ToolDefinition("listProjects", spec.description(), Map.of(),
                params -> {
                    List<String> projects = mcpTool.listProjects();
                    return projects.isEmpty()
                            ? "No searchable projects available."
                            : String.join(", ", projects);
                }, spec);
    }

    // ==================== Result formatting ====================

    private String formatHits(String query, String project, List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "No results found for grep query: \"" + query + "\""
                    + (project != null ? " in project \"" + project + "\"" : "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(hits.size()).append(" result(s) for grep query: \"").append(query).append("\"\n\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append("Result ").append(i + 1).append(":\n");
            if (hit.file() != null) sb.append("  File: ").append(hit.file()).append("\n");
            if (hit.lineFrom() != null) {
                sb.append("  Lines: ").append(hit.lineFrom()).append("-")
                        .append(hit.lineTo() != null ? hit.lineTo() : "?").append("\n");
            }
            if (hit.snippet() != null) sb.append("  Snippet:\n").append(hit.snippet()).append("\n");
            if (hit.chunkId() != null) sb.append("  Chunk: ").append(hit.chunkId()).append("\n");
            sb.append("\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
    }

    private String formatFileView(String file, FileView view) {
        if (view == null) {
            return "No file view returned for: \"" + file + "\"";
        }
        StringBuilder sb = new StringBuilder();
        // Candidates first: an ambiguous path also carries an error message, but the candidate
        // list is the actionable information for the model.
        if (view.candidates() != null && !view.candidates().isEmpty()) {
            sb.append("Ambiguous file path. Candidates:\n");
            for (String cand : view.candidates()) {
                sb.append("  - ").append(cand).append("\n");
            }
            return sb.toString();
        }
        if (view.error() != null) {
            return "ERROR reading file: " + view.error();
        }
        sb.append("File: ").append(view.file() != null ? view.file() : file).append("\n");
        if (view.totalLines() != null) sb.append("Total lines: ").append(view.totalLines()).append("\n");
        if (view.returnedLines() != null) sb.append("Returned lines: ").append(view.returnedLines()).append("\n");
        if (view.truncated()) sb.append("(Content truncated at 100k char cap)\n");
        sb.append("\n--- FILE CONTENT ---\n");
        sb.append(view.content() != null ? view.content() : "(No content available)");
        return truncate(sb.toString(), READER_RESULT_CAP);
    }

    private String formatSymbolResult(String method, SymbolReadResult result) {
        if (result == null) {
            return "No result returned for symbol: \"" + method + "\"";
        }
        if (result.error() != null) {
            return "ERROR reading symbol: " + result.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(method).append("\n");
        if (result.matches() != null && !result.matches().isEmpty()) {
            sb.append("Found ").append(result.matches().size()).append(" match(es):\n\n");
            for (int i = 0; i < result.matches().size(); i++) {
                var match = result.matches().get(i);
                sb.append("Match ").append(i + 1).append(":\n");
                if (match.fqn() != null) sb.append("  FQN: ").append(match.fqn()).append("\n");
                if (match.file() != null) sb.append("  File: ").append(match.file()).append("\n");
                if (match.lineFrom() != null) {
                    sb.append("  Lines: ").append(match.lineFrom()).append("-")
                            .append(match.lineTo() != null ? match.lineTo() : "?").append("\n");
                }
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
        return truncate(sb.toString(), READER_RESULT_CAP);
    }

    // ==================== Argument coercion ====================

    /** Returns a trimmed, non-blank string value or null. */
    static String string(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object value = params.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    static Integer integer(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number n) return n.intValue();
        String text = string(params, key);
        if (text == null) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double number(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number n) return n.doubleValue();
        String text = string(params, key);
        if (text == null) return null;
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean bool(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Boolean b) return b;
        String text = string(params, key);
        return text == null ? null : Boolean.valueOf(text);
    }

    /** Truncate a string to maxChars, appending a marker if truncated. */
    static String truncate(String text, int maxChars) {
        if (text == null) return "(null)";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [truncated, total " + text.length() + " chars]";
    }
}




