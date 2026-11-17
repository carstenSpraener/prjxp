package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP surface of the historical tool contract ({@code prjxp.hub.tool-profile=p1}).
 * <p>
 * The data methods live in {@link PrjxpToolService}; this class only carries the annotations so
 * that P1 and P2 can expose different tool sets from the same implementation. P1 is the control
 * arm of the A/B measurement and must stay byte-identical in its descriptions and parameters.
 */
@Component
@ConditionalOnExpression("${prjxp.hub.enabled:false} && '${prjxp.hub.tool-profile:p1}'.toLowerCase() == 'p1'")
@RequiredArgsConstructor
public class PrjxpMcpTool {

    private final PrjxpToolService toolService;

    @McpTool(name = "vectorSearch", description = PrjxpToolDocs.VECTOR_SEARCH)
    public String vectorSearch(
            @McpToolParam(description = "Concise English keyword query; keep identifiers verbatim.", required = true)
            String userQuestion,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String projectName,
            @McpToolParam(description = "Leave at 0.85; lower only if a search returns nothing.", required = false)
            Double similarity,
            @McpToolParam(description = "Maximum number of FILES, default 10 (max 20).", required = false)
            Integer maxResults,
            @McpToolParam(description = "code (default) | docs | all. Retry with all for prose documentation.", required = false)
            String sourceType,
            @McpToolParam(description = "Include test sources (default false).", required = false)
            Boolean includeTests,
            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget) {
        return toolService.vectorSearch(userQuestion, projectName, similarity, maxResults, sourceType,
                includeTests, tokenBudget);
    }

    @McpTool(name = "listProjects", description = PrjxpToolDocs.LIST_PROJECTS)
    public List<ProjectSummary> listProjects() {
        return toolService.listProjects();
    }

    @McpTool(name = "grep", description = PrjxpToolDocs.GREP)
    public String grep(
            @McpToolParam(description = "Exact search string, e.g. 'public MClass createMClass'.", required = true)
            String query,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Optional language filter (e.g. 'java').", required = false)
            String language,
            @McpToolParam(description = "Max hits after grouping, default 30 (max 100).", required = false)
            Integer limit,
            @McpToolParam(description = "Include test sources (default true).", required = false)
            Boolean includeTests) {
        return toolService.grep(query, project, language, limit, includeTests);
    }

    @McpTool(name = "readFile", description = PrjxpToolDocs.READ_FILE)
    public FileView readFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Optional 1-based start line.", required = false)
            Integer offset,
            @McpToolParam(description = "Optional max lines from offset (default 100).", required = false)
            Integer limit,
            @McpToolParam(description = "true = whole file only for files ≤200 lines; capped at 200 lines / 20k chars.", required = false)
            Boolean full) {
        return toolService.readFile(file, project, offset, limit, full);
    }

    @McpTool(name = "readBySignature", description = PrjxpToolDocs.READ_BY_SIGNATURE)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = "Method name or 'FQN#name' for the body; class FQNs return the class frame.", required = true)
            String method,
            @McpToolParam(description = "Optional containing class FQN to disambiguate.", required = false)
            String container,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project) {
        return toolService.readBySignature(method, container, project);
    }

    @McpTool(name = "listFiles", description = PrjxpToolDocs.LIST_FILES)
    public String listFiles(
            @McpToolParam(description = "Path prefix or glob ('*' spans '/'); empty = all.", required = false)
            String path,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Max files to return (default 50, max 500).", required = false)
            Integer limit) {
        return toolService.listFiles(path, project, limit);
    }

    @McpTool(name = "graphFile", description = PrjxpToolDocs.GRAPH_FILE)
    public String graphFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Max neighbours per direction (default 30, max 500).", required = false)
            Integer maxEdges,
            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal,
            @McpToolParam(description = "Skip this many per direction (default 0).", required = false)
            Integer offset,
            @McpToolParam(description = "FULL (default) = all edges; AGGREGATED = per-module counts + top classes.", required = false)
            String contentLevel,
            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget) {
        return toolService.graphFile(file, project, maxEdges, includeExternal, offset, contentLevel, tokenBudget);
    }

    @McpTool(name = "graphNode", description = PrjxpToolDocs.GRAPH_NODE)
    public String graphNode(
            @McpToolParam(description = "Node id: class FQN or method id; bare names match as suffix.", required = true)
            String symbol,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Max neighbours per direction (default 30, max 500).", required = false)
            Integer maxEdges,
            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal,
            @McpToolParam(description = "Skip this many per direction (default 0).", required = false)
            Integer offset,
            @McpToolParam(description = "FULL (default) = all edges; AGGREGATED = per-module counts + top classes.", required = false)
            String contentLevel,
            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget) {
        return toolService.graphNode(symbol, project, maxEdges, includeExternal, offset, contentLevel, tokenBudget);
    }

    @McpTool(name = "graphSearch", description = PrjxpToolDocs.GRAPH_SEARCH)
    public String graphSearch(
            @McpToolParam(description = "Short English keyword query; keep identifiers verbatim.", required = true)
            String question,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "bfs (default) = broad context, dfs = path.", required = false)
            String mode,
            @McpToolParam(description = "Traversal depth, 1-6 (default 3).", required = false)
            Integer depth,
            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget,
            @McpToolParam(description = "Optional edge types: CALL, CONTAINS, FIELD, ...", required = false)
            List<String> contextFilter,
            @McpToolParam(description = "Max seeds, 1-5 (default 3).", required = false)
            Integer maxSeeds) {
        return toolService.graphSearch(question, project, mode, depth, tokenBudget, contextFilter, maxSeeds);
    }

    @McpTool(name = "listConfig", description = PrjxpToolDocs.LIST_CONFIG)
    public String listConfig(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget) {
        return toolService.listConfig(project, tokenBudget);
    }

    @McpTool(name = "graphCluster", description = PrjxpToolDocs.GRAPH_CLUSTER)
    public ClusterView graphCluster(
            @McpToolParam(description = "Cluster id, e.g. 'c6' (from vectorSearch output).", required = true)
            String clusterId,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Top members to return (default 10, max 50).", required = false)
            Integer topN) {
        return toolService.graphCluster(clusterId, project, topN);
    }

    @McpTool(name = "graphClusterOverview", description = PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW)
    public ClusterOverviewView graphClusterOverview(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Clusters to return (default 10, max 50).", required = false)
            Integer topN) {
        return toolService.graphClusterOverview(project, topN);
    }

    @McpTool(name = "semanticGraphCluster", description = PrjxpToolDocs.SEMANTIC_GRAPH_CLUSTER)
    public SemanticClusterView semanticGraphCluster(
            @McpToolParam(description = "Semantic cluster id (sN).", required = true)
            String clusterId,
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max members, default 10 (max 50).", required = false)
            Integer topN) {
        return toolService.semanticGraphCluster(clusterId, project, topN);
    }

    @McpTool(name = "semanticGraphNode", description = PrjxpToolDocs.SEMANTIC_GRAPH_NODE)
    public SemanticNodeView semanticGraphNode(
            @McpToolParam(description = "Semantic node id.", required = true)
            String nodeId,
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max neighbours, default 20 (max 100).", required = false)
            Integer maxEdges) {
        return toolService.semanticGraphNode(nodeId, project, maxEdges);
    }

    @McpTool(name = "semanticGraphOverview", description = PrjxpToolDocs.SEMANTIC_GRAPH_OVERVIEW)
    public SemanticOverviewView semanticGraphOverview(
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max clusters, default 10 (max 50).", required = false)
            Integer topN) {
        return toolService.semanticGraphOverview(project, topN);
    }

    @McpTool(name = "readChunks", description = PrjxpToolDocs.READ_CHUNKS)
    public String readChunks(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Comma-separated chunk_ids (from vectorSearch output), max 10; prefer 1-3.", required = true)
            String ids) {
        return toolService.readChunks(project, ids);
    }
}
