package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
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
public class PrjxpToolService {
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

    public String vectorSearch(
            String userQuestion,

            String projectName,

            Double similarity,

            Integer maxResults,

            String sourceType,

            Boolean includeTests,

            Integer tokenBudget
    ) {
        return vectorSearch(userQuestion, projectName, similarity, maxResults, sourceType, includeTests,
                tokenBudget, false);
    }

    /**
     * P2 vector search: pointers plus a short snippet, de-duplicated chunk ids and an explicit
     * {@code readChunks} hint. Same query path as P1, only the rendering density differs.
     */
    public String vectorSearchP2(
            String userQuestion,
            String projectName,
            Double similarity,
            Integer maxResults,
            String sourceType,
            Boolean includeTests,
            Integer tokenBudget
    ) {
        return vectorSearch(userQuestion, projectName, similarity, maxResults, sourceType, includeTests,
                tokenBudget, true);
    }

    private String vectorSearch(String userQuestion, String projectName, Double similarity, Integer maxResults,
                                String sourceType, Boolean includeTests, Integer tokenBudget, boolean p2) {

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
        String context = p2
                ? enrichment.enrich(projectName, userQuestion, similarity, maxResults, level, includeTests,
                        SourceTypeFilter.mimeFilters(effectiveSourceType), 240, true, true)
                : enrichment.enrich(projectName, userQuestion, similarity, maxResults, level, includeTests,
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

    public List<ProjectSummary> listProjects() {
        return projectRegistry.projectInfos().stream()
                .filter(info -> projectRegistry.isSearchable(info.name()))   // hub: READY only; static mode: all (unchanged result)
                .map(info -> new ProjectSummary(
                        info.name(),
                        info.liveUpdate() != null ? info.liveUpdate().lastGeneratedAt() : null))
                .toList();
    }

    public String grep(
            String query,

            String project,

            String language,

            Integer limit,

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

    /**
     * P2 grep: line hits with a {@code file:line} anchor. {@code mode} defaults to substring;
     * an invalid regex is reported as an error note instead of an empty result.
     */
    public String grepP2(String query, String project, String mode, String language, Integer limit,
                         Boolean includeTests) {
        GrepMode grepMode = GrepMode.parse(mode, GrepMode.SUBSTRING);
        int maxHits = limit == null ? 20 : Math.max(1, Math.min(limit, SearchLimits.MAX_LIMIT));
        boolean tests = includeTests == null || includeTests;
        GrepResult result = grepSearchService.searchMatches(query == null ? "" : query.trim(),
                project, language, maxHits, tests, grepMode);
        return ToolResultCaps.cap(renderMatches(query, grepMode, result), ToolResultCaps.SEARCH);
    }

    private String renderMatches(String query, GrepMode mode, GrepResult result) {
        if (result == null) {
            return "No results found.";
        }
        if (result.hits().isEmpty()) {
            String prefix = result.note() != null ? result.note() + "\n" : "";
            return prefix + "No matches for \"" + query + "\" (mode=" + mode.name().toLowerCase(Locale.ROOT) + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result.hits().size()).append(" hit(s) for \"").append(query)
                .append("\" (mode=").append(mode.name().toLowerCase(Locale.ROOT)).append("):\n");
        for (SearchHit hit : result.hits()) {
            sb.append(hit.file());
            if (hit.lineFrom() != null) {
                sb.append(':').append(hit.lineFrom());
            }
            if (hit.snippet() != null && !hit.snippet().isBlank()) {
                sb.append(": ").append(hit.snippet());
            }
            sb.append('\n');
        }
        if (result.note() != null) {
            sb.append(result.note()).append('\n');
        }
        return sb.toString();
    }

    /** P2 symbol read: a class FQN additionally returns its first method bodies. */
    public SymbolReadResult readBySignatureP2(String method, String container, String project,
                                              Boolean includeBodies, Integer maxBodies) {
        int bodies = maxBodies == null ? 3 : Math.max(0, Math.min(maxBodies, 10));
        boolean withBodies = includeBodies == null || includeBodies;
        return capSymbolResult(symbolReaderService.readBySignature(method, container, project,
                withBodies, bodies));
    }

    public FileView readFile(
            String file,

            String project,

            Integer offset,

            Integer limit,

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

    public SymbolReadResult readBySignature(
            String method,

            String container,

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

    public String listFiles(
            String path,

            String project,

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

    public String graphFile(
            String file,

            String project,

            Integer maxEdges,

            Boolean includeExternal,

            Integer offset,

            String contentLevel,

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

    public String graphNode(
            String symbol,

            String project,

            Integer maxEdges,

            Boolean includeExternal,

            Integer offset,

            String contentLevel,

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

    public String graphSearch(
            String question,

            String project,

            String mode,

            Integer depth,

            Integer tokenBudget,

            List<String> contextFilter,

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

    public String listConfig(
            String project,

            Integer tokenBudget
    ) {
        return ResultRenderers.renderConfigCatalog(configCatalogService.list(project),
                ToolResultCaps.clampTokenBudget(tokenBudget));
    }

    public ClusterView graphCluster(
            String clusterId,
            String project,
            Integer topN) {
        return fileGraphService.graphCluster(clusterId, project, topN);
    }

    public ClusterOverviewView graphClusterOverview(
            String project,
            Integer topN) {
        return fileGraphService.graphClusterOverview(project, topN);
    }

    public SemanticClusterView semanticGraphCluster(
            String clusterId,
            String project,
            Integer topN) {
        if (StringUtils.isBlank(clusterId)) {
            return SemanticClusterView.unavailable("Parameter 'clusterId' ist erforderlich.");
        }
        return semanticGraphService.semanticCluster(clusterId.trim(), project, topN);
    }

    public SemanticNodeView semanticGraphNode(
            String nodeId,
            String project,
            Integer maxEdges) {
        if (StringUtils.isBlank(nodeId)) {
            return SemanticNodeView.unavailable("Parameter 'nodeId' ist erforderlich.");
        }
        return semanticGraphService.semanticNode(nodeId.trim(), project, maxEdges);
    }

    public SemanticOverviewView semanticGraphOverview(
            String project,
            Integer topN) {
        return semanticGraphService.semanticOverview(project, topN);
    }

    public String readChunks(
            String project,

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
