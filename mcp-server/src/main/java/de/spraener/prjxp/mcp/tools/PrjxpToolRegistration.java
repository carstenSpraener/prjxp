package de.spraener.prjxp.mcp.tools;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.mcp.ClusterMember;
import de.spraener.prjxp.mcp.ClusterOverviewView;
import de.spraener.prjxp.mcp.ClusterSummary;
import de.spraener.prjxp.mcp.ClusterView;
import de.spraener.prjxp.mcp.GraphView;
import de.spraener.prjxp.mcp.GrepResult;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import de.spraener.prjxp.mcp.PrjxpToolDocs;
import de.spraener.prjxp.mcp.ResultRenderers;
import de.spraener.prjxp.mcp.ToolResultCaps;
import de.spraener.prjxp.mcp.ProjectSummary;
import de.spraener.prjxp.mcp.SemanticClusterView;
import de.spraener.prjxp.mcp.SemanticNeighbor;
import de.spraener.prjxp.mcp.SemanticNodeView;
import de.spraener.prjxp.mcp.SemanticOverviewView;
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
 * Registers the fourteen prjxp tools in the central {@link ToolRegistry} so that they are
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
    private static final int SEARCH_RESULT_CAP = ToolResultCaps.SEARCH;
    private static final int READER_RESULT_CAP = ToolResultCaps.READER;

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
        register(graphSearchTool());
        register(listConfigTool());
        register(graphClusterTool());
        register(graphClusterOverviewTool());
        register(semanticGraphClusterTool());
        register(semanticGraphNodeTool());
        register(semanticGraphOverviewTool());
        register(readChunksTool());
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
                .description(PrjxpToolDocs.VECTOR_SEARCH)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("userQuestion",
                                "Concise English keyword query; keep identifiers verbatim.")
                        .addStringProperty("projectName",
                                "Project name (listProjects).")
                        .addNumberProperty("similarity",
                                "Leave at 0.85; lower only if a search returns nothing.")
                        .addIntegerProperty("maxResults",
                                "Maximum number of FILES, default 10 (max 20).")
                        .addStringProperty("sourceType",
                                "code (default) | docs | all. Retry with all for prose documentation.")
                        .addBooleanProperty("includeTests",
                                "Include test sources (default false).")
                        .addIntegerProperty("tokenBudget",
                                "Max output tokens, default 2000 (max 4000).")
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
                    // The MCP method already applies the token budget, so do not re-truncate.
                    return mcpTool.vectorSearch(question, string(params, "projectName"),
                            number(params, "similarity"), integer(params, "maxResults"),
                            string(params, "sourceType"), bool(params, "includeTests"),
                            integer(params, "tokenBudget"));
                }, spec);
    }

    private ToolDefinition grepTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grep")
                .description(PrjxpToolDocs.GREP)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "Exact search string, e.g. 'public MClass createMClass'.")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addStringProperty("language", "Optional language filter (e.g. 'java').")
                        .addIntegerProperty("limit", "Max hits after grouping, default 30 (max 100).")
                        .addBooleanProperty("includeTests",
                                "Include test sources (default true).")
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
                    // Strategy: grep returns the file list only (grep -l); content comes via
                    // readBySignature/readFile. No contentLevel knob on the search tools.
                    ContentLevel level = ContentLevel.NAMES;
                    GrepResult result = mcpTool.grepResult(query, project, string(params, "language"),
                            integer(params, "limit"), bool(params, "includeTests"), level);
                    return formatHits(query, project, result, level);
                }, spec);
    }

    private ToolDefinition readFileTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("readFile")
                .description(PrjxpToolDocs.READ_FILE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file",
                                "File path (absolute, relative, or name; lenient).")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("offset", "Optional 1-based start line.")
                        .addIntegerProperty("limit", "Optional max lines from offset (default 100).")
                        .addBooleanProperty("full", "true = whole file only for files ≤200 lines; capped at 200 lines / 20k chars.")
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
                            integer(params, "offset"), integer(params, "limit"), bool(params, "full"));
                    return formatFileView(file, view);
                }, spec);
    }

    private ToolDefinition readBySignatureTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("readBySignature")
                .description(PrjxpToolDocs.READ_BY_SIGNATURE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("method",
                                "Method name or 'FQN#name' for the body; class FQNs return the class frame.")
                        .addStringProperty("container",
                                "Optional containing class FQN to disambiguate.")
                        .addStringProperty("project", "Project name (listProjects).")
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
                .description(PrjxpToolDocs.LIST_PROJECTS)
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
                .description(PrjxpToolDocs.LIST_FILES)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path",
                                "Path prefix or glob ('*' spans '/'); empty = all.")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("limit", "Max files to return (default 50, max 500).")
                        .required("project")
                        .build())
                .build();

        return new ToolDefinition("listFiles", spec.description(),
                Map.of("path", "Path prefix or glob", "project", "Required project name"),
                params -> {
                    FileListResult result = mcpTool.listFilesResult(string(params, "path"),
                            string(params, "project"), integer(params, "limit"));
                    return formatFileList(result);
                }, spec);
    }

    private ToolDefinition graphFileTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphFile")
                .description(PrjxpToolDocs.GRAPH_FILE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file",
                                "File path (absolute, relative, or name; lenient).")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("maxEdges", "Max neighbours per direction (default 30, max 500).")
                        .addBooleanProperty("includeExternal", "Include JDK/library edges (default false).")
                        .addIntegerProperty("offset", "Skip this many per direction (default 0).")
                        .addStringProperty("contentLevel",
                                "FULL (default) = all edges; AGGREGATED = per-module counts + top classes.")
                        .addIntegerProperty("tokenBudget", "Max output tokens, default 2000 (max 4000).")
                        .required("file", "project")
                        .build())
                .build();

        return new ToolDefinition("graphFile", spec.description(),
                Map.of("file", "File path", "project", "Required project name",
                        "maxEdges", "Maximum neighbours per direction", "includeExternal", "Include external edges",
                        "offset", "Pagination offset per direction", "contentLevel", "FULL or AGGREGATED",
                        "tokenBudget", "Max output tokens"),
                params -> {
                    String file = string(params, "file");
                    if (file == null) {
                        return "ERROR: 'file' parameter is required for graphFile.";
                    }
                    GraphView view = mcpTool.graphFileView(file, string(params, "project"),
                            integer(params, "maxEdges"), bool(params, "includeExternal"), integer(params, "offset"),
                            string(params, "contentLevel"));
                    return formatGraph(view, integer(params, "tokenBudget"));
                }, spec);
    }

    private ToolDefinition graphNodeTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphNode")
                .description(PrjxpToolDocs.GRAPH_NODE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("symbol",
                                "Node id: class FQN or method id; bare names match as suffix.")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("maxEdges", "Max neighbours per direction (default 30, max 500).")
                        .addBooleanProperty("includeExternal", "Include JDK/library edges (default false).")
                        .addIntegerProperty("offset", "Skip this many per direction (default 0).")
                        .addStringProperty("contentLevel",
                                "FULL (default) = all edges; AGGREGATED = per-module counts + top classes.")
                        .addIntegerProperty("tokenBudget", "Max output tokens, default 2000 (max 4000).")
                        .required("symbol", "project")
                        .build())
                .build();

        return new ToolDefinition("graphNode", spec.description(),
                Map.of("symbol", "Node id (FQN or method id)", "project", "Required project name",
                        "maxEdges", "Maximum neighbours per direction", "includeExternal", "Include external edges",
                        "offset", "Pagination offset per direction", "contentLevel", "FULL or AGGREGATED",
                        "tokenBudget", "Max output tokens"),
                params -> {
                    String symbol = string(params, "symbol");
                    if (symbol == null) {
                        return "ERROR: 'symbol' parameter is required for graphNode.";
                    }
                    GraphView view = mcpTool.graphNodeView(symbol, string(params, "project"),
                            integer(params, "maxEdges"), bool(params, "includeExternal"), integer(params, "offset"),
                            string(params, "contentLevel"));
                    return formatGraph(view, integer(params, "tokenBudget"));
                }, spec);
    }

    private ToolDefinition graphSearchTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphSearch")
                .description(PrjxpToolDocs.GRAPH_SEARCH)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("question",
                                "Short English keyword query; keep identifiers verbatim.")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addStringProperty("mode", "bfs (default) = broad context, dfs = path.")
                        .addIntegerProperty("depth", "Traversal depth, 1-6 (default 3).")
                        .addIntegerProperty("tokenBudget", "Max output tokens, default 2000 (max 4000).")
                        .addProperty("contextFilter", dev.langchain4j.model.chat.request.json.JsonArraySchema.builder()
                                .description("Optional edge types: CALL, CONTAINS, FIELD, ...")
                                .items(dev.langchain4j.model.chat.request.json.JsonStringSchema.builder().build())
                                .build())
                        .addIntegerProperty("maxSeeds", "Max seeds, 1-5 (default 3).")
                        .required("question", "project")
                        .build())
                .build();

        return new ToolDefinition("graphSearch", spec.description(),
                Map.of("question", "Keyword query", "project", "Required project name",
                        "mode", "bfs or dfs", "depth", "Traversal depth", "tokenBudget", "Max output tokens",
                        "contextFilter", "Edge-type filter", "maxSeeds", "Number of seeds"),
                params -> {
                    String question = string(params, "question");
                    if (question == null) {
                        return "ERROR: 'question' parameter is required for graphSearch.";
                    }
                    return mcpTool.graphSearch(question, string(params, "project"),
                            string(params, "mode"), integer(params, "depth"), integer(params, "tokenBudget"),
                            stringList(params, "contextFilter"), integer(params, "maxSeeds"));
                }, spec);
    }

    private ToolDefinition listConfigTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("listConfig")
                .description(PrjxpToolDocs.LIST_CONFIG)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("tokenBudget", "Max output tokens, default 2000 (max 4000).")
                        .required("project")
                        .build())
                .build();

        return new ToolDefinition("listConfig", spec.description(),
                Map.of("project", "Required project name", "tokenBudget", "Max output tokens"),
                params -> mcpTool.listConfig(string(params, "project"), integer(params, "tokenBudget")),
                spec);
    }

    private ToolDefinition graphClusterTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("graphCluster")
                .description(PrjxpToolDocs.GRAPH_CLUSTER)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("clusterId",
                                "Cluster id, e.g. 'c6' (from vectorSearch output).")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("topN", "Top members to return (default 10, max 50).")
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
                .description(PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("topN", "Clusters to return (default 10, max 50).")
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

    private ToolDefinition semanticGraphClusterTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("semanticGraphCluster")
                .description(PrjxpToolDocs.SEMANTIC_GRAPH_CLUSTER)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("clusterId", "Semantic cluster id (sN).")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("topN", "Top members to return (default 10, max 50).")
                        .required("clusterId", "project")
                        .build())
                .build();
        return new ToolDefinition("semanticGraphCluster", spec.description(),
                Map.of("clusterId", "Semantic cluster id", "project", "Required project name",
                        "topN", "Number of members to return"),
                params -> {
                    String clusterId = string(params, "clusterId");
                    if (clusterId == null) {
                        return "ERROR: 'clusterId' parameter is required for semanticGraphCluster.";
                    }
                    return formatSemanticCluster(mcpTool.semanticGraphCluster(
                            clusterId, string(params, "project"), integer(params, "topN")));
                }, spec);
    }

    private ToolDefinition semanticGraphNodeTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("semanticGraphNode")
                .description(PrjxpToolDocs.SEMANTIC_GRAPH_NODE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("nodeId", "Semantic node id from a semantic pointer or graph result.")
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("maxEdges", "Maximum neighbours (default 20, max 100).")
                        .required("nodeId", "project")
                        .build())
                .build();
        return new ToolDefinition("semanticGraphNode", spec.description(),
                Map.of("nodeId", "Semantic node id", "project", "Required project name",
                        "maxEdges", "Maximum neighbours to return"),
                params -> {
                    String nodeId = string(params, "nodeId");
                    if (nodeId == null) {
                        return "ERROR: 'nodeId' parameter is required for semanticGraphNode.";
                    }
                    return formatSemanticNode(mcpTool.semanticGraphNode(
                            nodeId, string(params, "project"), integer(params, "maxEdges")));
                }, spec);
    }

    private ToolDefinition semanticGraphOverviewTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("semanticGraphOverview")
                .description(PrjxpToolDocs.SEMANTIC_GRAPH_OVERVIEW)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("project", "Project name (listProjects).")
                        .addIntegerProperty("topN", "Clusters to return (default 10, max 50).")
                        .required("project")
                        .build())
                .build();
        return new ToolDefinition("semanticGraphOverview", spec.description(),
                Map.of("project", "Required project name", "topN", "Number of clusters to return"),
                params -> formatSemanticOverview(mcpTool.semanticGraphOverview(
                        string(params, "project"), integer(params, "topN"))), spec);
    }

    private ToolDefinition readChunksTool() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("readChunks")
                .description(PrjxpToolDocs.READ_CHUNKS)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("project", "Project name (listProjects).")
                        .addStringProperty("ids", "Comma-separated chunk_ids (from vectorSearch output), max 10; prefer 1-3.")
                        .required("project", "ids")
                        .build())
                .build();

        return new ToolDefinition("readChunks", spec.description(),
                Map.of("project", "Required project name", "ids", "Comma-separated chunk_ids"),
                params -> {
                    String project = string(params, "project");
                    String ids = string(params, "ids");
                    if (project == null || ids == null) {
                        return "ERROR: 'project' and 'ids' are required for readChunks.";
                    }
                    return truncate(mcpTool.readChunks(project, ids), SEARCH_RESULT_CAP);
                }, spec);
    }

    // ==================== Result formatting ====================

    private String formatSemanticCluster(SemanticClusterView view) {
        if (view == null) {
            return "No semantic cluster view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder result = new StringBuilder()
                .append("Project: ").append(view.project())
                .append("\nSemantic cluster: ").append(view.clusterId())
                .append("\nTotal members: ").append(view.totalMembers()).append("\n");
        for (var member : view.members()) {
            result.append("  - ").append(member.id());
            if (member.file() != null) result.append(" (").append(member.file()).append(")");
            if (member.mime() != null) result.append(" [").append(member.mime()).append("]");
            if (member.preview() != null && !member.preview().isBlank()) {
                result.append("\n    ").append(member.preview());
            }
            result.append("\n");
        }
        return truncate(result.toString(), SEARCH_RESULT_CAP);
    }

    private String formatSemanticNode(SemanticNodeView view) {
        if (view == null) {
            return "No semantic node view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder result = new StringBuilder()
                .append("Project: ").append(view.project())
                .append("\nSemantic node: ").append(view.nodeId());
        if (view.file() != null) result.append("\nFile: ").append(view.file());
        if (view.mime() != null) result.append("\nMIME: ").append(view.mime());
        if (view.clusterId() != null) result.append("\nSemantic cluster: ").append(view.clusterId());
        result.append("\nNeighbours:\n");
        for (SemanticNeighbor neighbor : view.neighbors()) {
            result.append("  - ").append(neighbor.id())
                    .append(" score=").append(String.format(java.util.Locale.ROOT, "%.4f", neighbor.score()));
            if (neighbor.file() != null) result.append(" (").append(neighbor.file()).append(")");
            result.append("\n");
        }
        return truncate(result.toString(), SEARCH_RESULT_CAP);
    }

    private String formatSemanticOverview(SemanticOverviewView view) {
        if (view == null) {
            return "No semantic overview returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder result = new StringBuilder()
                .append("Project: ").append(view.project())
                .append("\nSemantic clusters: ").append(view.totalClusters());
        if (view.model() != null) result.append("\nEmbedding model: ").append(view.model());
        if (view.warning() != null) result.append("\nWarning: ").append(view.warning());
        result.append("\n");
        for (var cluster : view.clusters()) {
            result.append("  - ").append(cluster.clusterId()).append(" (")
                    .append(cluster.size()).append(" members)");
            if (!cluster.topFiles().isEmpty()) {
                result.append(": ").append(String.join(", ", cluster.topFiles()));
            }
            result.append("\n");
        }
        return truncate(result.toString(), SEARCH_RESULT_CAP);
    }

    private String formatFileList(FileListResult result) {
        return truncate(ResultRenderers.renderFileList(result), SEARCH_RESULT_CAP);
    }

    private String formatHits(String query, String project, GrepResult result, ContentLevel level) {
        List<SearchHit> hits = result == null ? List.of() : result.hits();
        if (hits.isEmpty()) {
            return "No results found for grep query: \"" + query + "\""
                    + (project != null ? " in project \"" + project + "\"" : "");
        }
        if (level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED) {
            return formatNamesHits(hits, result);
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
        // Tell the model when the list was cut off so it can raise the limit instead of
        // assuming it has seen every matching file.
        if (result.note() != null) {
            sb.append(result.note()).append("\n");
        }
        return truncate(sb.toString(), SEARCH_RESULT_CAP);
    }

    /** {@code NAMES}/{@code AGGREGATED}: one "count  path" line per file, no per-hit wrapper. */
    private String formatNamesHits(List<SearchHit> hits, GrepResult result) {
        StringBuilder sb = new StringBuilder();
        for (SearchHit hit : hits) {
            String line = hit.snippet();
            if (line == null || line.isBlank()) {
                line = hit.file();
            }
            if (line != null && !line.isBlank()) {
                sb.append(line).append("\n");
            }
        }
        if (result.note() != null) {
            sb.append(result.note()).append("\n");
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
        if (view.truncated()) sb.append("(Content truncated — page with offset/limit, or read a symbol with readBySignature(FQN#name); full=true is capped at 200 lines)\n");
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

    private String formatGraph(GraphView view, Integer tokenBudget) {
        return ToolResultCaps.capTokens(ResultRenderers.renderGraph(view),
                ToolResultCaps.clampTokenBudget(tokenBudget));
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

    /** Accepts a real list or a comma-separated string (both are sent by MCP clients). */
    static List<String> stringList(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value == null) return null;
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = string(params, key);
        if (text == null) return null;
        return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Truncate a string to maxChars, appending a marker if truncated. */
    static String truncate(String text, int maxChars) {
        if (text == null) return "(null)";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [truncated, total " + text.length() + " chars]";
    }
}

