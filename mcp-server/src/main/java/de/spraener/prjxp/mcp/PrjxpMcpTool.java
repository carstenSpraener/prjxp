package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log
public class PrjxpMcpTool {
    private final GRPromptEnrichment enrichment;

    private final GrepSearchService grepSearchService;

    private final ReaderService readerService;

    private final SymbolReaderService symbolReaderService;

    private final ProjectRegistry projectRegistry;

    private final FileListService fileListService;

    private final FileGraphService fileGraphService;

    @McpTool(name = "vectorSearch", description = PrjxpToolDocs.VECTOR_SEARCH)
    public String vectorSearch(
            @McpToolParam(description = "Search prompt for vector retrieval.", required = true)
            String userQuestion,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String projectName,

            @McpToolParam(description = "Leave at 0.85; lower only if a search returns nothing.", required = false)
            Double similarity,

            @McpToolParam(description = "Maximum number of FILES, default 10 (max 20).", required = false)
            Integer maxResults,

            @McpToolParam(description = "Deprecated; contentLevel wins. Legacy TRUE still means SKELETON.", required = false)
            Boolean skeletonsOnly,

            @McpToolParam(description = "POINTERS (default) hints only, SKELETON signatures, FULL bodies.", required = false)
            String contentLevel,

            @McpToolParam(description = "code (default) | docs | all. Retry with all for prose documentation.", required = false)
            String sourceType,

            @McpToolParam(description = "Include test sources (default false).", required = false)
            Boolean includeTests
    ) {

        String prefix = """
                """;
        try {
            projectRegistry.ensureSearchable(projectName);   // explicit error instead of silent fallback
        } catch (UnknownProjectException e) {
            return "ERROR: " + e.getMessage();
        }
        projectName = projectRegistry.resolve(projectName);
        if (similarity == null || similarity < 0.0 || similarity > 1.0) {
            similarity = 0.85;
        }
        if (maxResults == null) {
            maxResults = 10;   // cheap default (was 20)
        }
        maxResults = Math.max(1, Math.min(20, maxResults));   // clamp to 1..20
        if (includeTests == null) {
            includeTests = Boolean.FALSE;   // semantic discovery: tests are noise
        }
        ContentLevel level;
        if (contentLevel == null || contentLevel.isBlank()) {
            // no explicit contentLevel: legacy skeletonsOnly=TRUE still means SKELETON; otherwise the cheap POINTERS default
            level = Boolean.TRUE.equals(skeletonsOnly) ? ContentLevel.SKELETON : ContentLevel.POINTERS;
        } else {
            level = ContentLevel.parse(contentLevel);
        }
        String effectiveSourceType = (sourceType == null || sourceType.isBlank()) ? SourceTypeFilter.CODE : sourceType;
        log.info(String.format("searching context for '%s' for project '%s' (contentLevel=%s, sourceType=%s).",
                userQuestion, projectName, level, effectiveSourceType));
        String context = enrichment.enrich(projectName, userQuestion, similarity, maxResults, level, includeTests,
                SourceTypeFilter.mimeFilters(effectiveSourceType));
        context = appendDeadEndHint(context, effectiveSourceType);

        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
        return result;
    }

    /**
     * When a code-scoped search comes back empty, nudge the model to retry with {@code sourceType=all}
     * in case the answer lives in prose documentation.
     */
    private String appendDeadEndHint(String context, String effectiveSourceType) {
        boolean codeScoped = SourceTypeFilter.CODE.equalsIgnoreCase(
                effectiveSourceType == null ? "" : effectiveSourceType.trim());
        if (context != null && context.contains("kein valider Kontext") && codeScoped) {
            return context + "\nKeine Code-Treffer. Mit sourceType=all wiederholen, wenn Doku/Prosa gemeint ist.";
        }
        return context;
    }

    @McpTool(name = "listProjects", description = PrjxpToolDocs.LIST_PROJECTS)
    public List<ProjectSummary> listProjects() {
        return projectRegistry.projectInfos().stream()
                .filter(info -> projectRegistry.isSearchable(info.name()))   // hub: READY only; static mode: all (unchanged result)
                .map(info -> new ProjectSummary(
                        info.name(),
                        info.liveUpdate() != null ? info.liveUpdate().lastGeneratedAt() : null))
                .toList();
    }

    @McpTool(name = "grep", description = PrjxpToolDocs.GREP)
    public List<SearchHit> grep(
            @McpToolParam(description = "Exact search string, e.g. 'public MClass createMClass'.", required = true)
            String query,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Optional language filter (e.g. 'java').", required = false)
            String language,

            @McpToolParam(description = "Max hits after grouping, default 10 (max 100).", required = false)
            Integer limit,

            @McpToolParam(description = "POINTERS enumerates, SKELETON signatures, FULL (default) bodies.", required = false)
            String contentLevel,

            @McpToolParam(description = "Include test sources (default true).", required = false)
            Boolean includeTests
    ) {
        if (limit == null) {
            limit = 0;
        }
        if (limit == 0) limit = SearchLimits.DEFAULT_LIMIT;
        limit = SearchLimits.clamp(limit);
        if (includeTests == null) {
            includeTests = Boolean.TRUE;   // exact search: tests are deliberate intent
        }

        return grepSearchService.search(query.trim(), project, language, limit, includeTests, ContentLevel.parse(contentLevel));
    }

    @McpTool(name = "readFile", description = PrjxpToolDocs.READ_FILE)
    public FileView readFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Optional 1-based start line.", required = false)
            Integer offset,

            @McpToolParam(description = "Optional max lines from offset.", required = false)
            Integer limit
    ) {
        if (StringUtils.isEmpty(file)) {
            return FileView.error(file, "Parameter 'file' is required.");
        }
        return readerService.read(file.trim(), project, offset, limit);
    }

    @McpTool(name = "readBySignature", description = PrjxpToolDocs.READ_BY_SIGNATURE)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = "Method name or FQN; class FQNs return the class frame.", required = true)
            String method,

            @McpToolParam(description = "Optional containing class FQN to disambiguate.", required = false)
            String container,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project
    ) {
        return symbolReaderService.readBySignature(method, container, project);
    }

    @McpTool(name = "listFiles", description = PrjxpToolDocs.LIST_FILES)
    public FileListResult listFiles(
            @McpToolParam(description = "Path prefix or glob ('*' spans '/'); empty = all.", required = false)
            String path,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Max files to return (default 50, max 500).", required = false)
            Integer limit
    ) {
        int safeLimit = (limit == null) ? SearchLimits.LIST_DEFAULT_LIMIT : SearchLimits.clampList(limit);
        return fileListService.list(path, project, safeLimit);
    }

    @McpTool(name = "graphFile", description = PrjxpToolDocs.GRAPH_FILE)
    public GraphView graphFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Max neighbours per direction (default 30, max 100).", required = false)
            Integer maxEdges,

            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal
    ) {
        if (StringUtils.isBlank(file)) {
            return GraphView.unavailable("Parameter 'file' ist erforderlich.");
        }
        return fileGraphService.graphFile(file.trim(), project, maxEdges, includeExternal);
    }

    @McpTool(name = "graphNode", description = PrjxpToolDocs.GRAPH_NODE)
    public GraphView graphNode(
            @McpToolParam(description = "Node id: class FQN or method id; bare names match as suffix.", required = true)
            String symbol,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Max neighbours per direction (default 30, max 100).", required = false)
            Integer maxEdges,

            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal
    ) {
        if (StringUtils.isBlank(symbol)) {
            return GraphView.unavailable("Parameter 'symbol' ist erforderlich.");
        }
        return fileGraphService.graphNode(symbol.trim(), project, maxEdges, includeExternal);
    }

    @McpTool(name = "graphCluster", description = PrjxpToolDocs.GRAPH_CLUSTER)
    public ClusterView graphCluster(
            @McpToolParam(description = "Cluster id, e.g. 'c6' (from vectorSearch output).", required = true)
            String clusterId,
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Top members to return (default 10, max 50).", required = false)
            Integer topN) {
        return fileGraphService.graphCluster(clusterId, project, topN);
    }

    @McpTool(name = "graphClusterOverview", description = PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW)
    public ClusterOverviewView graphClusterOverview(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,
            @McpToolParam(description = "Clusters to return (default 10, max 50).", required = false)
            Integer topN) {
        return fileGraphService.graphClusterOverview(project, topN);
    }

}
