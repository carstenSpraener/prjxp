package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final SemanticGraphService semanticGraphService;
    private final PxChunkDaoProvider chunkDaoProvider;
    private final GraphSearchService graphSearchService;
    private final ConfigCatalogService configCatalogService;

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
            Integer tokenBudget
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
        // Strategy: search tools return pointers only — no contentLevel knob, so the agent
        // cannot escalate to SKELETON/FULL. Content comes via readBySignature/readFile.
        ContentLevel level = ContentLevel.POINTERS;
        String effectiveSourceType = (sourceType == null || sourceType.isBlank()) ? SourceTypeFilter.CODE : sourceType;
        log.info(String.format("searching context for '%s' for project '%s' (contentLevel=%s, sourceType=%s).",
                userQuestion, projectName, level, effectiveSourceType));
        String context = enrichment.enrich(projectName, userQuestion, similarity, maxResults, level, includeTests,
                SourceTypeFilter.mimeFilters(effectiveSourceType));
        context = appendDeadEndHint(context, effectiveSourceType);

        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
        return ToolResultCaps.capTokens(result, ToolResultCaps.clampTokenBudget(tokenBudget));
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
            Boolean includeTests
    ) {
        // Strategy: grep returns the file list only (grep -l) — no contentLevel knob, so the
        // agent cannot escalate to SKELETON/FULL. Content comes via readBySignature/readFile.
        ContentLevel level = ContentLevel.NAMES;
        String text = GrepTextRenderer.render(
                grepResult(query, project, language, limit, includeTests, level), level, query);
        return ToolResultCaps.cap(text, ToolResultCaps.SEARCH);
    }

    /**
     * Data method behind {@link #grep}; also used by the hub registry, which formats the result
     * itself. The MCP annotation path converts a {@link String} verbatim to {@code TextContent},
     * so {@link #grep} must not return the record.
     */
    public GrepResult grepResult(String query, String project, String language, Integer limit,
                                 Boolean includeTests, ContentLevel level) {
        if (limit == null) {
            limit = 0;
        }
        if (limit == 0) limit = SearchLimits.DEFAULT_LIMIT;
        limit = SearchLimits.clamp(limit);
        if (includeTests == null) {
            includeTests = Boolean.TRUE;   // exact search: tests are deliberate intent
        }
        return grepSearchService.searchDetailed(query.trim(), project, language, limit, includeTests, level);
    }

    /**
     * grep -l default: the cheap file list with hit counts. Unknown or blank values fall back
     * to NAMES — never to FULL, so a typo cannot escalate to the most expensive mode.
     */
    public static ContentLevel resolveGrepLevel(String contentLevel) {
        return ContentLevel.parse(contentLevel, ContentLevel.NAMES);
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
            Boolean full
    ) {
        if (StringUtils.isEmpty(file)) {
            return FileView.error(file, "Parameter 'file' is required.");
        }
        boolean isFull = Boolean.TRUE.equals(full);
        FileView view = readerService.read(file.trim(), project, offset, limit, isFull);
        // full=true is a larger escape hatch, but never uncapped: a single call must not
        // blow the context window. Above the cap the model must page or readBySignature.
        return capFileView(view, isFull ? ToolResultCaps.READER_FULL : ToolResultCaps.READER);
    }

    /** Caps the reader content so a single call cannot blow the context window. */
    private FileView capFileView(FileView view, int cap) {
        if (view == null || view.content() == null || view.content().length() <= cap) {
            return view;
        }
        return new FileView(view.file(), view.language(), view.chunkCount(),
                ToolResultCaps.cap(view.content(), cap), true,
                view.totalLines(), view.returnedLines(), view.candidates(), view.error());
    }

    @McpTool(name = "readBySignature", description = PrjxpToolDocs.READ_BY_SIGNATURE)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = "Method name or 'FQN#name' for the body; class FQNs return the class frame.", required = true)
            String method,

            @McpToolParam(description = "Optional containing class FQN to disambiguate.", required = false)
            String container,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project
    ) {
        return capSymbolResult(symbolReaderService.readBySignature(method, container, project));
    }

    /** Caps each body so up to 10 matches cannot blow the context window. */
    private SymbolReadResult capSymbolResult(SymbolReadResult result) {
        if (result == null || result.error() != null || result.matches() == null) {
            return result;
        }
        List<MethodView> matches = result.matches().stream()
                .map(m -> m.body() != null && m.body().length() > ToolResultCaps.READER
                        ? new MethodView(m.fqn(), m.file(), m.lineFrom(), m.lineTo(), m.javadoc(),
                                ToolResultCaps.cap(m.body(), ToolResultCaps.READER))
                        : m)
                .toList();
        return new SymbolReadResult(matches, result.remainingFqns(), result.error());
    }

    @McpTool(name = "listFiles", description = PrjxpToolDocs.LIST_FILES)
    public String listFiles(
            @McpToolParam(description = "Path prefix or glob ('*' spans '/'); empty = all.", required = false)
            String path,

            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Max files to return (default 50, max 500).", required = false)
            Integer limit
    ) {
        // The MCP annotation path converts a String verbatim to TextContent, so the result is
        // rendered and capped here; the hub registry calls listFilesResult and formats it itself.
        return ToolResultCaps.cap(ResultRenderers.renderFileList(listFilesResult(path, project, limit)),
                ToolResultCaps.SEARCH);
    }

    /**
     * Data method behind {@link #listFiles}; also used by the hub registry, which formats the
     * result itself. The MCP annotation path converts a {@link String} verbatim to
     * {@code TextContent}, so {@link #listFiles} must not return the record.
     */
    public FileListResult listFilesResult(String path, String project, Integer limit) {
        int safeLimit = (limit == null) ? SearchLimits.LIST_DEFAULT_LIMIT : SearchLimits.clampList(limit);
        return fileListService.list(path, project, safeLimit);
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
            Integer tokenBudget
    ) {
        // The MCP annotation path converts a String verbatim to TextContent, so the result is
        // rendered and capped here; the hub registry calls graphFileView and formats it itself.
        return ToolResultCaps.capTokens(ResultRenderers.renderGraph(
                graphFileView(file, project, maxEdges, includeExternal, offset, contentLevel)),
                ToolResultCaps.clampTokenBudget(tokenBudget));
    }

    /**
     * Data method behind {@link #graphFile}; also used by the hub registry, which formats the
     * result itself. The MCP annotation path converts a {@link String} verbatim to
     * {@code TextContent}, so {@link #graphFile} must not return the record.
     */
    public GraphView graphFileView(String file, String project, Integer maxEdges, Boolean includeExternal,
                                   Integer offset, String contentLevel) {
        if (StringUtils.isBlank(file)) {
            return GraphView.unavailable("Parameter 'file' ist erforderlich.");
        }
        return fileGraphService.graphFile(file.trim(), project, maxEdges, includeExternal, offset, ContentLevel.parse(contentLevel));
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
            Integer tokenBudget
    ) {
        // The MCP annotation path converts a String verbatim to TextContent, so the result is
        // rendered and capped here; the hub registry calls graphNodeView and formats it itself.
        return ToolResultCaps.capTokens(ResultRenderers.renderGraph(
                graphNodeView(symbol, project, maxEdges, includeExternal, offset, contentLevel)),
                ToolResultCaps.clampTokenBudget(tokenBudget));
    }

    /**
     * Data method behind {@link #graphNode}; also used by the hub registry, which formats the
     * result itself. The MCP annotation path converts a {@link String} verbatim to
     * {@code TextContent}, so {@link #graphNode} must not return the record.
     */
    public GraphView graphNodeView(String symbol, String project, Integer maxEdges, Boolean includeExternal,
                                   Integer offset, String contentLevel) {
        if (StringUtils.isBlank(symbol)) {
            return GraphView.unavailable("Parameter 'symbol' ist erforderlich.");
        }
        return fileGraphService.graphNode(symbol.trim(), project, maxEdges, includeExternal, offset, ContentLevel.parse(contentLevel));
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
            Integer maxSeeds
    ) {
        return ResultRenderers.renderGraphSearch(
                graphSearchView(project, question, mode, depth, tokenBudget, contextFilter, maxSeeds),
                ToolResultCaps.clampTokenBudget(tokenBudget));
    }

    /** Data method behind {@link #graphSearch}; also used by the hub registry. */
    public GraphSearchView graphSearchView(String project, String question, String mode, Integer depth,
                                           Integer tokenBudget, List<String> contextFilter, Integer maxSeeds) {
        if (StringUtils.isBlank(question)) {
            return GraphSearchView.unavailable("Parameter 'question' ist erforderlich.");
        }
        String effectiveMode = StringUtils.isBlank(mode) ? "bfs" : mode.trim();
        return graphSearchService.search(project, question.trim(), effectiveMode,
                depth == null ? 3 : depth,
                ToolResultCaps.clampTokenBudget(tokenBudget),
                parseContextFilter(contextFilter),
                maxSeeds == null ? 3 : maxSeeds);
    }

    private static Set<EdgeType> parseContextFilter(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<EdgeType> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                try {
                    result.add(EdgeType.valueOf(part.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // unknown edge type -> ignore, the filter is best-effort
                }
            }
        }
        return result;
    }

    @McpTool(name = "listConfig", description = PrjxpToolDocs.LIST_CONFIG)
    public String listConfig(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Max output tokens, default 2000 (max 4000).", required = false)
            Integer tokenBudget
    ) {
        return ResultRenderers.renderConfigCatalog(configCatalogService.list(project),
                ToolResultCaps.clampTokenBudget(tokenBudget));
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

    @McpTool(name = "semanticGraphCluster", description = PrjxpToolDocs.SEMANTIC_GRAPH_CLUSTER)
    public SemanticClusterView semanticGraphCluster(
            @McpToolParam(description = "Semantic cluster id (sN).", required = true)
            String clusterId,
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max members, default 10 (max 50).", required = false)
            Integer topN) {
        if (StringUtils.isBlank(clusterId)) {
            return SemanticClusterView.unavailable("Parameter 'clusterId' ist erforderlich.");
        }
        return semanticGraphService.semanticCluster(clusterId.trim(), project, topN);
    }

    @McpTool(name = "semanticGraphNode", description = PrjxpToolDocs.SEMANTIC_GRAPH_NODE)
    public SemanticNodeView semanticGraphNode(
            @McpToolParam(description = "Semantic node id.", required = true)
            String nodeId,
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max neighbours, default 20 (max 100).", required = false)
            Integer maxEdges) {
        if (StringUtils.isBlank(nodeId)) {
            return SemanticNodeView.unavailable("Parameter 'nodeId' ist erforderlich.");
        }
        return semanticGraphService.semanticNode(nodeId.trim(), project, maxEdges);
    }

    @McpTool(name = "semanticGraphOverview", description = PrjxpToolDocs.SEMANTIC_GRAPH_OVERVIEW)
    public SemanticOverviewView semanticGraphOverview(
            @McpToolParam(description = "Project name.", required = true)
            String project,
            @McpToolParam(description = "Max clusters, default 10 (max 50).", required = false)
            Integer topN) {
        return semanticGraphService.semanticOverview(project, topN);
    }

    @McpTool(name = "readChunks", description = PrjxpToolDocs.READ_CHUNKS)
    public String readChunks(
            @McpToolParam(description = "Project name (listProjects).", required = true)
            String project,

            @McpToolParam(description = "Comma-separated chunk_ids (from vectorSearch output), max 10; prefer 1-3.", required = true)
            String ids
    ) {
        if (StringUtils.isBlank(project) || StringUtils.isBlank(ids)) {
            return "ERROR: 'project' and 'ids' are required.";
        }
        PxChunkDao dao = chunkDaoProvider.get(project.trim()).orElse(null);
        if (dao == null) {
            return "ERROR: Project '" + project + "' not found or not searchable.";
        }
        List<String> idList = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(10)
                .toList();
        if (idList.isEmpty()) {
            return "ERROR: No valid chunk_ids provided.";
        }

        StringBuilder sb = new StringBuilder();
        int cap = 8000;   // total content cap
        for (String id : idList) {
            List<PxChunk> parts = dao.findById(id);
            if (parts == null || parts.isEmpty()) {
                sb.append("## ").append(id).append("\n(not found)\n\n");
                continue;
            }
            PxChunk combined = PxChunk.combine(parts);
            if (combined == null) {
                continue;
            }
            String content = combined.getContent() == null ? "" : combined.getContent();
            // Per-chunk cap: 2000 chars to keep the total bounded
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "\n... [truncated]";
            }
            sb.append("## ").append(id);
            if (combined.getFile() != null) {
                sb.append(" (").append(combined.getFile()).append(")");
            }
            sb.append("\n").append(content).append("\n\n");
            if (sb.length() > cap) {
                sb.append("\n... [total cap reached, remaining chunks omitted]\n");
                break;
            }
        }
        return sb.toString();
    }

}
