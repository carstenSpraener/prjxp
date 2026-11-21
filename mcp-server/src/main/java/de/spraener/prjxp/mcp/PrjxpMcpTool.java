package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileView;

import de.spraener.prjxp.common.model.SymbolReadResult;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The MCP surface of the hub.
 * <p>
 * Contract properties:
 * <ul>
 *   <li>every tool takes an explicit {@code project}; {@code listProjects} answers which projects
 *       exist, so one hub serves all indexed projects,</li>
 *   <li>{@code grep} returns {@code file:line} hits instead of a bare file list,</li>
 *   <li>{@code vectorSearch} adds a snippet and a {@code readChunks} hint and de-duplicates ids,</li>
 *   <li>{@code readBySignature} appends method bodies to a class frame,</li>
 *   <li>descriptions are decision guides, not a catalogue.</li>
 * </ul>
 */
@Component
@ConditionalOnExpression("${prjxp.hub.enabled:false}")
@RequiredArgsConstructor
public class PrjxpMcpTool {

    private final PrjxpToolService toolService;

    @McpTool(name = "listProjects", description = PrjxpToolDocs.LIST_PROJECTS)
    public List<ProjectSummary> listProjects() {
        return toolService.listProjects();
    }

    @McpTool(name = "vectorSearch", description = PrjxpToolDocs.VECTOR_SEARCH)
    public String vectorSearch(
            @McpToolParam(description = "Short English keyword query; identifiers verbatim.", required = true)
            String userQuestion,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Similarity floor, default 0.85.", required = false)
            Double similarity,
            @McpToolParam(description = "Max files, default 10 (max 20).", required = false)
            Integer maxResults,
            @McpToolParam(description = "code (default) | docs | all.", required = false)
            String sourceType,
            @McpToolParam(description = "Include test sources (default false).", required = false)
            Boolean includeTests,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.vectorSearch(userQuestion, project, similarity, maxResults,
                sourceType, includeTests, tokenBudget);
    }

    @McpTool(name = "grep", description = PrjxpToolDocs.GREP)
    public String grep(
            @McpToolParam(description = "Text or pattern to find.", required = true)
            String query,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "substring (default) | exact | regex.", required = false)
            String mode,
            @McpToolParam(description = "Optional language filter (e.g. 'java').", required = false)
            String language,
            @McpToolParam(description = "Max hits, default 20 (max 100).", required = false)
            Integer limit,
            @McpToolParam(description = "Include test sources (default true).", required = false)
            Boolean includeTests) {
        return toolService.grep(query, project, mode, language, limit, includeTests);
    }

    @McpTool(name = "readFile", description = PrjxpToolDocs.READ_FILE)
    public FileView readFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "1-based start line.", required = false)
            Integer offset,
            @McpToolParam(description = "Max lines from offset (default 100).", required = false)
            Integer limit,
            @McpToolParam(description = "Whole file only for files ≤200 lines; capped.", required = false)
            Boolean full) {
        return toolService.readFile(file, project, offset, limit, full);
    }

    @McpTool(name = "readBySignature", description = PrjxpToolDocs.READ_BY_SIGNATURE)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = "'FQN#name', class FQN, or bare method name.", required = true)
            String method,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Containing class FQN to disambiguate.", required = false)
            String container,
            @McpToolParam(description = "Class FQNs: append method bodies (default true).", required = false)
            Boolean includeBodies,
            @McpToolParam(description = "Max appended bodies, default 3 (max 10).", required = false)
            Integer maxBodies) {
        return toolService.readBySignature(method, container, project,
                includeBodies, maxBodies);
    }

    @McpTool(name = "listFiles", description = PrjxpToolDocs.LIST_FILES)
    public String listFiles(
            @McpToolParam(description = "Path prefix or glob ('*' spans '/'); empty = all.", required = false)
            String path,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max files, default 50 (max 500).", required = false)
            Integer limit) {
        return toolService.listFiles(path, project, limit);
    }

    @McpTool(name = "graphFile", description = PrjxpToolDocs.GRAPH_FILE)
    public String graphFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max neighbours per direction, default 30.", required = false)
            Integer maxEdges,
            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal,
            @McpToolParam(description = "Skip this many per direction.", required = false)
            Integer offset,
            @McpToolParam(description = "FULL (default) | AGGREGATED.", required = false)
            String contentLevel,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.graphFile(file, project, maxEdges, includeExternal, offset,
                contentLevel, tokenBudget);
    }

    @McpTool(name = "graphNode", description = PrjxpToolDocs.GRAPH_NODE)
    public String graphNode(
            @McpToolParam(description = "Class FQN or method id; bare names match as suffix.", required = true)
            String symbol,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max neighbours per direction, default 30.", required = false)
            Integer maxEdges,
            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal,
            @McpToolParam(description = "Skip this many per direction.", required = false)
            Integer offset,
            @McpToolParam(description = "FULL (default) | AGGREGATED.", required = false)
            String contentLevel,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.graphNode(symbol, project, maxEdges, includeExternal, offset,
                contentLevel, tokenBudget);
    }

    @McpTool(name = "graphSearch", description = PrjxpToolDocs.GRAPH_SEARCH)
    public String graphSearch(
            @McpToolParam(description = "Short English keyword query; identifiers verbatim.", required = true)
            String question,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "bfs (default) | dfs.", required = false)
            String mode,
            @McpToolParam(description = "Traversal depth, 1-6 (default 3).", required = false)
            Integer depth,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget,
            @McpToolParam(description = "Optional edge types: CALL, CONTAINS, FIELD, …", required = false)
            List<String> contextFilter,
            @McpToolParam(description = "Max seeds, 1-5 (default 3).", required = false)
            Integer maxSeeds) {
        return toolService.graphSearch(question, project, mode, depth, tokenBudget,
                contextFilter, maxSeeds);
    }

    @McpTool(name = "graphCluster", description = PrjxpToolDocs.GRAPH_CLUSTER)
    public ClusterView graphCluster(
            @McpToolParam(description = "Cluster id, e.g. 'c6' (from vectorSearch output).", required = true)
            String clusterId,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Top members to return, default 10.", required = false)
            Integer topN) {
        return toolService.graphCluster(clusterId, project, topN);
    }

    @McpTool(name = "graphClusterOverview", description = PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW)
    public ClusterOverviewView graphClusterOverview(
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Clusters to return, default 10.", required = false)
            Integer topN) {
        return toolService.graphClusterOverview(project, topN);
    }

    @McpTool(name = "semanticGraphCluster", description = PrjxpToolDocs.SEMANTIC_GRAPH_CLUSTER)
    public SemanticClusterView semanticGraphCluster(
            @McpToolParam(description = "Semantic cluster id (sN).", required = true)
            String clusterId,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max members, default 10.", required = false)
            Integer topN) {
        return toolService.semanticGraphCluster(clusterId, project, topN);
    }

    @McpTool(name = "semanticGraphNode", description = PrjxpToolDocs.SEMANTIC_GRAPH_NODE)
    public SemanticNodeView semanticGraphNode(
            @McpToolParam(description = "Semantic node id.", required = true)
            String nodeId,
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max neighbours, default 20.", required = false)
            Integer maxEdges) {
        return toolService.semanticGraphNode(nodeId, project, maxEdges);
    }

    @McpTool(name = "semanticGraphOverview", description = PrjxpToolDocs.SEMANTIC_GRAPH_OVERVIEW)
    public SemanticOverviewView semanticGraphOverview(
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max clusters, default 10.", required = false)
            Integer topN) {
        return toolService.semanticGraphOverview(project, topN);
    }

    @McpTool(name = "listConfig", description = PrjxpToolDocs.LIST_CONFIG)
    public String listConfig(
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.listConfig(project, tokenBudget);
    }

    @McpTool(name = "readChunks", description = PrjxpToolDocs.READ_CHUNKS)
    public String readChunks(
            @McpToolParam(description = "Indexed project name, e.g. prjxp or legacy.", required = true)
            String project,
            @McpToolParam(description = "Comma-separated chunk_ids, max 10; prefer 1–3.", required = true)
            String ids) {
        return toolService.readChunks(project, ids);
    }
}
