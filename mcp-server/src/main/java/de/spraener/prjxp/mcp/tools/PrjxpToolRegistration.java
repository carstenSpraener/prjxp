package de.spraener.prjxp.mcp.tools;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.mcp.ClusterMember;
import de.spraener.prjxp.mcp.ClusterOverviewView;
import de.spraener.prjxp.mcp.ClusterSummary;
import de.spraener.prjxp.mcp.ClusterView;
import de.spraener.prjxp.mcp.GraphNeighbor;
import de.spraener.prjxp.mcp.GraphView;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import de.spraener.prjxp.mcp.ProjectSummary;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registers the ten prjxp tools in the central {@link ToolRegistry} so that they are
 * available for native function calling — executed in-process as plain Java calls on
 * {@link PrjxpMcpTool}, without a JSON-RPC round trip.
 * <p>
 * Registered early (highest precedence) so that external MCP or Groovy tools cannot shadow them:
 * the first registration of a name wins.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
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
        register(listFilesTool());
        register(graphFileTool());
        register(graphNodeTool());
        register(graphClusterTool());
        register(graphClusterOverviewTool());
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
                        includeTests: include test sources (default: false for vectorSearch, true for grep). Pass true to vectorSearch when asking about tests.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("userQuestion",
                                "A targeted, standalone search prompt optimized for vector retrieval.")
                        .addStringProperty("projectName",
                                "Required project name. Call listProjects to discover valid names.")
                        .addNumberProperty("similarity",
                                "Similarity threshold 0.0-1.0. Higher is more precise. Default 0.85.")
                        .addIntegerProperty("maxResults",
                                "Maximum number of results to return. Default 20.")
                        .addBooleanProperty("skeletonsOnly",
                                "DEPRECATED alias for contentLevel. Kept for backward compatibility.")
                        .addStringProperty("contentLevel",
                                "How much content to emit: FULL (full method bodies), SKELETON (signatures only — the default), or POINTERS (tool hints only). Authoritative over skeletonsOnly.")
                        .addStringProperty("sourceType",
                                "Filter by source type: all (default), code (everything except prose documents), or docs (only markdown/pdf prose).")
                        .addBooleanProperty("includeTests",
                                "Include test sources (default: false for vectorSearch, true for grep). Pass true to vectorSearch when asking about tests.")
                        .required("userQuestion", "projectName")
                        .build())
                .build();

        return new ToolDefinition("vectorSearch", spec.description(),
                Map.of("userQuestion", "The search prompt", "projectName", "Required project name"),
                params -> {
                    String question = string(params, "userQuestion");
                    if (question == null) {
                        return "ERROR: 'userQuestion' parameter is required for vectorSearch.";
                    }
                    String result = mcpTool.vectorSearch(question, string(params, "projectName"),
                            number(params, "similarity"), integer(params, "maxResults"),
                            bool(params, "skeletonsOnly"), string(params, "contentLevel"),
                            string(params, "sourceType"), bool(params, "includeTests"));
                    return truncate(result, SEARCH_RESULT_CAP);
                }, spec);
    }

    private ToolDefinition grepTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grep")
                .description("""
                        SEARCH TOOL (level 2 of 3): Exact full-text search over all chunks of a project.
                        Use the exact method signature as query to retrieve its FULL BODY.
                        includeTests: include test sources (default: false for vectorSearch, true for grep). Pass false to grep to exclude test files.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "Exact search string, e.g. 'public MClass createMClass'.")
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addStringProperty("language", "Optional language filter, e.g. 'java' or 'ts'.")
                        .addIntegerProperty("limit", "Maximum number of results (default 10, max 100).")
                        .addStringProperty("contentLevel",
                                "How much content to emit per hit: FULL (bodies — the default), SKELETON (signatures only) or POINTERS (tool hints only). Same semantics as vectorSearch.")
                        .addBooleanProperty("includeTests",
                                "Include test sources (default: false for vectorSearch, true for grep). Pass false to grep to exclude test files.")
                        .required("query", "project")
                        .build())
                .build();

        return new ToolDefinition("grep", spec.description(),
                Map.of("query", "Exact search string", "project", "Required project name"),
                params -> {
                    String query = string(params, "query");
                    if (query == null) {
                        return "ERROR: 'query' parameter is required for grep.";
                    }
                    String project = string(params, "project");
                    List<SearchHit> hits = mcpTool.grep(query, project, string(params, "language"),
                            integer(params, "limit"), string(params, "contentLevel"), bool(params, "includeTests"));
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
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("offset", "Optional 1-based line number to start reading from.")
                        .addIntegerProperty("limit", "Optional maximum number of lines to return.")
                        .required("file", "project")
                        .build())
                .build();

        return new ToolDefinition("readFile", spec.description(),
                Map.of("file", "File path", "project", "Required project name"),
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
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .required("method", "project")
                        .build())
                .build();

        return new ToolDefinition("readBySignature", spec.description(),
                Map.of("method", "Method name or FQN", "project", "Required project name"),
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
                .description("DISCOVERY TOOL: Lists all projects that have a searchable index in this server, "
                        + "with the timestamp of each project's last index update.")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        return new ToolDefinition("listProjects", spec.description(), Map.of(),
                params -> {
                    List<ProjectSummary> projects = mcpTool.listProjects();
                    if (projects.isEmpty()) {
                        return "No searchable projects available.";
                    }
                    return projects.stream()
                            .map(p -> p.lastGeneratedAt() != null
                                    ? p.name() + " (last updated: " + Instant.ofEpochMilli(p.lastGeneratedAt()).toString() + ")"
                                    : p.name())
                            .collect(Collectors.joining(", "));
                }, spec);
    }

    private ToolDefinition listFilesTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("listFiles")
                .description("""
                        STRUCTURAL DISCOVERY TOOL (orthogonal to the search ladder): Lists which files exist in the project index.
                        Use it to explore the file tree and locate files by name pattern before reading them.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path",
                                "Path prefix or glob. '*' matches any characters including '/' (recursive). Empty = all files.")
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("limit", "Maximum number of files to return (default 50, max 500).")
                        .required("project")
                        .build())
                .build();

        return new ToolDefinition("listFiles", spec.description(),
                Map.of("path", "Path prefix or glob", "project", "Required project name"),
                params -> {
                    FileListResult result = mcpTool.listFiles(string(params, "path"),
                            string(params, "project"), integer(params, "limit"));
                    return formatFileList(result);
                }, spec);
    }

    private ToolDefinition graphFileTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphFile")
                .description("""
                        STRUCTURE TOOL: Returns the typed code-graph neighbours of one file — imported/extended/implemented/field/param/return/call edges with file paths.
                        Requires an indexed project with a graph artifact. Use graphNode for a specific symbol.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file",
                                "File path — absolute, project-root-relative, or just the file name (lenient: exact -> suffix -> basename).")
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("maxEdges", "Maximum neighbours per direction (default 30, max 100).")
                        .addBooleanProperty("includeExternal", "Include edges to nodes outside the project (default false).")
                        .required("file", "project")
                        .build())
                .build();

        return new ToolDefinition("graphFile", spec.description(),
                Map.of("file", "File path", "project", "Required project name",
                        "maxEdges", "Maximum neighbours per direction", "includeExternal", "Include external edges"),
                params -> {
                    String file = string(params, "file");
                    if (file == null) {
                        return "ERROR: 'file' parameter is required for graphFile.";
                    }
                    GraphView view = mcpTool.graphFile(file, string(params, "project"),
                            integer(params, "maxEdges"), bool(params, "includeExternal"));
                    return formatGraph(view);
                }, spec);
    }

    private ToolDefinition graphNodeTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphNode")
                .description("""
                        STRUCTURE TOOL: Returns the typed code-graph neighbours of one node id (`fqn` or `fqn#name(paramFQNs)`).
                        Lenient resolution: exact -> suffix. Ambiguous symbols return a candidate list. Use graphFile to start from a file.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("symbol",
                                "Node id — class FQN (`com.example.Foo`) or method id (`com.example.Foo#bar(int)`); bare names are matched as a suffix.")
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("maxEdges", "Maximum neighbours per direction (default 30, max 100).")
                        .addBooleanProperty("includeExternal", "Include edges to nodes outside the project (default false).")
                        .required("symbol", "project")
                        .build())
                .build();

        return new ToolDefinition("graphNode", spec.description(),
                Map.of("symbol", "Node id (FQN or method id)", "project", "Required project name",
                        "maxEdges", "Maximum neighbours per direction", "includeExternal", "Include external edges"),
                params -> {
                    String symbol = string(params, "symbol");
                    if (symbol == null) {
                        return "ERROR: 'symbol' parameter is required for graphNode.";
                    }
                    GraphView view = mcpTool.graphNode(symbol, string(params, "project"),
                            integer(params, "maxEdges"), bool(params, "includeExternal"));
                    return formatGraph(view);
                }, spec);
    }

    private ToolDefinition graphClusterTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphCluster")
                .description("""
                        STRUCTURE TOOL: Lists the most important members of one cluster (Community), ranked by degree centrality (weight = in + out).
                        USE AFTER a cluster id is known — e.g. from vectorSearch output `[cluster:c6]` or from graphNode.
                        Each member shows its (in, out) edge counts; call graphNode on a member for its typed edges.
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("clusterId",
                                "Cluster id, e.g. 'c6' (the value after 'cluster:' in vectorSearch output).")
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("topN", "Number of top members to return (default 10, max 50).")
                        .required("clusterId", "project")
                        .build())
                .build();

        return new ToolDefinition("graphCluster", spec.description(),
                Map.of("clusterId", "Cluster id", "project", "Required project name",
                        "topN", "Number of top members to return"),
                params -> {
                    String clusterId = string(params, "clusterId");
                    if (clusterId == null) return "ERROR: 'clusterId' parameter is required for graphCluster.";
                    ClusterView view = mcpTool.graphCluster(clusterId, string(params, "project"), integer(params, "topN"));
                    return formatCluster(view);
                }, spec);
    }

    private ToolDefinition graphClusterOverviewTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphClusterOverview")
                .description("""
                        STRUCTURE TOOL: A map of the project's clusters (Communities) — the N largest, each with its size and its top members by weight.
                        USE THIS to explore the architecture WITHOUT a starting class: it answers "what are the main areas of this codebase?".
                        Then drill down with graphCluster(clusterId).
                        """)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("project", "Required project name. Call listProjects to discover valid names.")
                        .addIntegerProperty("topN", "Number of clusters to return (default 10, max 50).")
                        .required("project")
                        .build())
                .build();

        return new ToolDefinition("graphClusterOverview", spec.description(),
                Map.of("project", "Required project name", "topN", "Number of clusters to return"),
                params -> {
                    ClusterOverviewView view = mcpTool.graphClusterOverview(string(params, "project"), integer(params, "topN"));
                    return formatClusterOverview(view);
                }, spec);
    }

    // ==================== Result formatting ====================

    private String formatFileList(FileListResult result) {
        if (result == null || result.files().isEmpty()) {
            return "No files found.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(result.files().size()).append(" file(s)");
        if (result.truncated()) {
            sb.append(" (truncated — narrow the pattern or raise the limit)");
        }
        sb.append(":\n");
        for (String file : result.files()) {
            sb.append("  - ").append(file).append("\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
    }

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

    private String formatGraph(GraphView view) {
        if (view == null) {
            return "No graph view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        if (view.candidates() != null && !view.candidates().isEmpty()) {
            StringBuilder sb = new StringBuilder("Ambiguous. Candidates:\n");
            for (String cand : view.candidates()) {
                sb.append("  - ").append(cand).append("\n");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(view.project()).append("\n");
        if (view.file() != null) {
            sb.append("File: ").append(view.file()).append("\n");
        } else {
            sb.append("Node: ").append(view.nodeId()).append("\n");
        }
        sb.append("  ").append(view.nodeId());
        if (view.clusterId() != null || view.weight() != null) {
            sb.append("   [cluster:").append(view.clusterId() == null ? "?" : view.clusterId())
                    .append(", weight:").append(view.weight() == null ? "?" : String.format(java.util.Locale.ROOT, "%.2f", view.weight()))
                    .append("]");
        }
        sb.append("\n");
        for (GraphNeighbor n : view.outgoing()) {
            sb.append("    ").append(n.type()).append(" -> ").append(n.neighborId())
                    .append(n.neighborFile() != null ? "  [" + n.neighborFile() + "]" : "")
                    .append("\n");
        }
        for (GraphNeighbor n : view.incoming()) {
            sb.append("    <- ").append(n.type()).append("  ").append(n.neighborId())
                    .append(n.neighborFile() != null ? "  [" + n.neighborFile() + "]" : "")
                    .append("\n");
        }
        if (view.outgoing().isEmpty() && view.incoming().isEmpty()) {
            sb.append("    (no edges)\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
    }

    private String formatCluster(ClusterView view) {
        if (view == null) {
            return "No cluster view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Cluster ").append(view.clusterId())
                .append(" — ").append(view.totalMembers()).append(" members (top ")
                .append(view.members().size()).append("):\n");
        for (ClusterMember m : view.members()) {
            sb.append("  ").append(String.format(java.util.Locale.ROOT, "%.2f", m.weight()))
                    .append("  in=").append(m.inDegree()).append(" out=").append(m.outDegree())
                    .append("  ").append(m.id())
                    .append("  (").append(m.file()).append(")\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
    }

    private String formatClusterOverview(ClusterOverviewView view) {
        if (view == null) {
            return "No cluster overview returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Clusters: ").append(view.totalClusters()).append(" total, top ")
                .append(view.clusters().size()).append(" largest:\n");
        for (ClusterSummary c : view.clusters()) {
            sb.append("  ").append(c.clusterId())
                    .append("  (").append(c.size()).append(" members):  ")
                    .append(String.join(", ", c.topMembers()))
                    .append("\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
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




