package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.QueryKeywords;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.enrichment.EnrichmentOutcome;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import de.spraener.prjxp.mcp.cache.SearchResultCache;
import de.spraener.prjxp.mcp.rest.QueryStatsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final QueryStatsCollector queryStats;
    private final SearchResultCache searchCache;

    /** Phase 03: the cached vector-search result — the rendered text plus the hit count for the query stats. */
    record ToolSearchResult(String text, int resultCount) {}

    /** Phase 02: start of the vector threshold ladder (the {@code similarity} override is clamped to [min-score, 1.0]). */
    @Value("${prjxp.mcp.vector.start-score:0.95}")
    double vectorStartScore = 0.95;

    /** Phase 02: floor of the vector threshold ladder; the ladder never descends below it. */
    @Value("${prjxp.mcp.vector.min-score:0.80}")
    double vectorMinScore = 0.80;

    /** Phase 02: only vector hits validate the search; lexical (BM25) hits alone keep the ladder running. */
    @Value("${prjxp.mcp.vector.require-vector-hit:true}")
    boolean requireVectorHit = true;

    /** Phase 02: the tool descends the threshold first; maxResult does not grow (no pointless 0.95 repeats). */
    @Value("${prjxp.mcp.vector.grow-max-results:false}")
    boolean growMaxResults = false;

    /** Phase 03: lexical keyword fallback when the search is a dead end: {@code keyword} (default) or {@code off}. */
    @Value("${prjxp.mcp.vector.fallback:keyword}")
    String vectorFallback = "keyword";

    /** Phase 03b: 0 = Gate aus; Fallback auch, wenn der beste Vektor-Treffer darunter liegt. */
    @Value("${prjxp.mcp.vector.confidence-score:0.85}")
    double vectorConfidenceScore = 0.85;

    public String vectorSearch(
            String userQuestion,

            String projectName,

            Double similarity,

            Integer maxResults,

            String sourceType,

            Boolean includeTests,

            Integer tokenBudget
    ) {
        long startedAt = System.nanoTime();
        String prefix = """
                """;
        try {
            projectRegistry.ensureSearchable(projectName);   // explicit error instead of silent fallback
        } catch (UnknownProjectException e) {
            return "ERROR: " + e.getMessage();
        }
        final String resolved = projectRegistry.resolve(projectName);
        // Phase 02: the similarity override stays, but is clamped to [vectorMinScore, 1.0] —
        // the threshold ladder must never start below its floor.
        double start = similarity == null ? vectorStartScore
                : Math.max(vectorMinScore, Math.min(1.0, similarity));
        if (maxResults == null) {
            maxResults = 10;   // cheap default (was 20)
        }
        maxResults = Math.max(1, Math.min(20, maxResults));   // clamp to 1..20
        if (includeTests == null) {
            includeTests = Boolean.FALSE;   // semantic discovery: tests are noise
        }
        String effectiveSourceType = (sourceType == null || sourceType.isBlank()) ? SourceTypeFilter.CODE : sourceType;
        final int effectiveMaxResults = maxResults;        // final copies: the loader lambda must capture them
        final Boolean effectiveIncludeTests = includeTests;

        // Phase 03: the rendered result is cached under the NORMALIZED parameters (clamped
        // start, effective source type) so equivalent calls share one entry. Validation
        // (ensureSearchable) stays in front of the cache: errors are never cached.
        String key = SearchResultCache.vectorToolKey(resolved, userQuestion, start, effectiveMaxResults,
                effectiveSourceType, effectiveIncludeTests, tokenBudget);
        SearchResultCache.Lookup<ToolSearchResult> lookup = searchCache.get(
                key, ToolSearchResult.class,
                () -> doVectorSearch(userQuestion, resolved, start, effectiveMaxResults, effectiveIncludeTests,
                        effectiveSourceType, tokenBudget, prefix));
        queryStats.recordQuery(userQuestion, elapsedMs(startedAt), lookup.value().resultCount(), lookup.hit());
        return lookup.value().text();
    }

    /**
     * Phase 03: the uncached vector search — enrichment, header, keyword fallback, token cap.
     * Runs only on a cache miss; the logging lives here for the same reason.
     */
    private ToolSearchResult doVectorSearch(String userQuestion, String resolved, double start, int maxResults,
                                            Boolean includeTests, String effectiveSourceType,
                                            Integer tokenBudget, String prefix) {
        // Strategy: search tools return pointers only — no contentLevel knob, so the agent
        // cannot escalate to SKELETON/FULL. Content comes via readBySignature/readFile.
        ContentLevel level = ContentLevel.POINTERS;
        log.info(String.format("searching context for '%s' for project '%s' (contentLevel=%s, sourceType=%s).",
                userQuestion, resolved, level, effectiveSourceType));
        SearchParams params = new SearchParams(maxResults, start);
        params.setContentLevel(level);
        params.setMaxContentLength(enrichment.getMaxContentLength());
        params.setMaxChunksPerFile(enrichment.getMaxChunksPerFile());
        params.setVectorFilters(SourceTypeFilter.mimeFilters(effectiveSourceType));
        params.setSnippetChars(240);
        params.setDeduplicate(true);
        params.setReadChunksHint(true);
        params.setMinScoreFloor(vectorMinScore);
        params.setRequireVectorHit(requireVectorHit);
        params.setGrowMaxResult(growMaxResults);
        params.setLowerThresholdFirst(true);
        EnrichmentOutcome outcome = enrichment.enrichOutcome(resolved, userQuestion, List.of(), params,
                enrichment::reIterate,
                context -> String.format("""
                        Relevante Information in '%s':
                        %s

                        """, resolved, context),
                includeTests,
                c -> c.length() > 0);
        // The tool formats its own header from the objective outcome — the enrichment header
        // (a cosine label) must not be printed twice, and a BM25 hit must not be labelled
        // with a cosine threshold it never passed.
        // Locale.ROOT: the header must read 0.95, not 0,95, regardless of the JVM locale.
        // Phase 03b: with vector hits the header shows the best (cosine) score — the value the
        // confidence gate below compares against.
        String vectorPart = outcome.hasVectorHit()
                ? String.format(Locale.ROOT, "Vektor-Treffer: %d (best %.2f)",
                        outcome.vectorHits(), outcome.bestVectorScore())
                : String.format(Locale.ROOT, "Vektor-Treffer: %d", outcome.vectorHits());
        String header = String.format(Locale.ROOT,
                "%s | Lexikal-Treffer: %d | Schwelle %.2f→%.2f (%d Fallbacks)\n"
                        + "Hinweis: Treffer mit score > 1.0 sind lexikalisch (BM25), nicht Cosine.\n",
                vectorPart, outcome.lexicalHits(), start,
                outcome.effectiveMinScore(), outcome.fallbackRounds());
        // Phase 03: no vector hit triggers the lexical keyword fallback — even when the AND-lexical
        // channel found something. Phase 03b: a weak vector hit (best score below the confidence
        // threshold) triggers it, too. The fallback replaces the pointer blocks with file:line hits,
        // so a query like "Vorgang service process" yields concrete locations instead of pointers.
        // Gate abschaltbar mit 0.
        boolean weakVector = vectorConfidenceScore > 0
                && outcome.hasVectorHit()
                && outcome.bestVectorScore() < vectorConfidenceScore;
        boolean deadEnd = !outcome.hasVectorHit() || weakVector;
        if (deadEnd && "keyword".equalsIgnoreCase(vectorFallback)) {
            List<String> terms = QueryKeywords.extract(userQuestion, 8);
            GrepResult fallback = grepSearchService.searchKeywords(terms, resolved, null, 3, includeTests,
                    SourceTypeFilter.mimeFilters(effectiveSourceType));
            String context = header + renderKeywordFallback(terms, fallback, effectiveSourceType);
            String result = String.format("%s\n%s", prefix, context);
            log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
            return new ToolSearchResult(ToolResultCaps.capTokens(result, ToolResultCaps.clampTokenBudget(tokenBudget)),
                    outcome.vectorHits() + outcome.lexicalHits());
        }
        String context = header + outcome.context();
        context = appendDeadEndHint(context, effectiveSourceType);

        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
        return new ToolSearchResult(ToolResultCaps.capTokens(result, ToolResultCaps.clampTokenBudget(tokenBudget)),
                outcome.vectorHits() + outcome.lexicalHits());
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * Phase 03: renders the lexical keyword fallback — at most 3 files, at most 2 lines per file,
     * each file followed by a read hint so the model reads instead of answering from grep lines
     * (the exact failure mode of the 02 benchmark). No hits: the searched terms plus the
     * historical dead-end hint.
     */
    private String renderKeywordFallback(List<String> terms, GrepResult fallback, String effectiveSourceType) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lexikalischer Fallback (Terme: ").append(String.join(", ", terms)).append("):\n");
        if (fallback == null || fallback.hits().isEmpty()) {
            sb.append("Keine Keyword-Treffer für: ").append(String.join(", ", terms));
            sb.append(deadEndHint(effectiveSourceType));
            return sb.toString();
        }
        Map<String, List<SearchHit>> byFile = new LinkedHashMap<>();
        for (SearchHit hit : fallback.hits()) {
            byFile.computeIfAbsent(hit.file(), key -> new ArrayList<>()).add(hit);
        }
        int files = 0;
        for (List<SearchHit> fileHits : byFile.values()) {
            if (files >= 3) {
                break;
            }
            files++;
            for (int i = 0; i < Math.min(2, fileHits.size()); i++) {
                SearchHit hit = fileHits.get(i);
                sb.append(hit.file()).append(':').append(hit.lineFrom()).append(": ")
                        .append(hit.snippet()).append('\n');
            }
            SearchHit first = fileHits.get(0);
            int line = first.lineFrom() == null ? 1 : first.lineFrom();
            sb.append("→ readFile(\"").append(first.file()).append("\", offset=")
                    .append(Math.max(1, line - 5)).append(", limit=40)\n");
            if (looksLikeFqn(first.chunkId())) {
                sb.append("→ readChunks(ids=\"").append(first.chunkId()).append("\")\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * A chunk id that looks like a code FQN (dotted, no path separator) — the {@code readChunks}
     * hint is only offered for these; a file-path chunk id is read with {@code readFile} instead.
     */
    private static boolean looksLikeFqn(String chunkId) {
        return chunkId != null && chunkId.contains(".") && !chunkId.contains("/");
    }

    /**
     * When a code-scoped search comes back empty, nudge the model to retry with {@code sourceType=all}
     * in case the answer lives in prose documentation.
     */
    private String appendDeadEndHint(String context, String effectiveSourceType) {
        if (context != null && context.contains("kein valider Kontext")) {
            return context + deadEndHint(effectiveSourceType);
        }
        return context;
    }

    /** The dead-end nudge for code-scoped searches; empty when the search was not code-scoped. */
    private String deadEndHint(String effectiveSourceType) {
        boolean codeScoped = SourceTypeFilter.CODE.equalsIgnoreCase(
                effectiveSourceType == null ? "" : effectiveSourceType.trim());
        return codeScoped ? "\nKeine Code-Treffer. Mit sourceType=all wiederholen, wenn Doku/Prosa gemeint ist." : "";
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

            String mode,

            String language,

            Integer limit,

            Boolean includeTests,

            String contentLevel
    ) {
        GrepMode grepMode = GrepMode.parse(mode, GrepMode.SUBSTRING);
        int maxHits = limit == null ? 20 : Math.max(1, Math.min(limit, SearchLimits.MAX_LIMIT));
        boolean tests = includeTests == null || includeTests;
        ContentLevel level = resolveGrepLevel(contentLevel);
        if (level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED) {
            // grep -l: the file list is the answer; content comes from readBySignature/readFile.
            GrepResult result = grepSearchService.searchFiles(query == null ? "" : query.trim(),
                    project, language, maxHits, tests, grepMode);
            return ToolResultCaps.cap(renderFileHits(query, grepMode, result), ToolResultCaps.SEARCH);
        }
        GrepResult result = grepSearchService.searchMatches(query == null ? "" : query.trim(),
                project, language, maxHits, tests, grepMode);
        return ToolResultCaps.cap(renderMatches(query, grepMode, result), ToolResultCaps.SEARCH);
    }

    /** grep -l output: one path per line, no line numbers, no snippets. */
    private String renderFileHits(String query, GrepMode mode, GrepResult result) {
        if (result == null) {
            return "No results found.";
        }
        if (result.hits().isEmpty()) {
            String prefix = result.note() != null ? result.note() + "\n" : "";
            return prefix + "No matches for \"" + query + "\" (mode=" + mode.name().toLowerCase(Locale.ROOT) + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(result.hits().size()).append(" file(s) for \"").append(query)
                .append("\" (mode=").append(mode.name().toLowerCase(Locale.ROOT)).append("):\n");
        for (SearchHit hit : result.hits()) {
            sb.append(hit.file()).append('\n');
        }
        if (result.note() != null) {
            sb.append(result.note()).append('\n');
        }
        return sb.toString();
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
        String trimmed = query == null ? "" : query.trim();
        // Same ranked path as the annotated grep tool: one ordering for both surfaces.
        if (level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED) {
            return grepSearchService.searchFiles(trimmed, project, language, limit, includeTests,
                    GrepMode.SUBSTRING);
        }
        return grepSearchService.searchMatches(trimmed, project, language, limit, includeTests,
                GrepMode.SUBSTRING);
    }

    /**
     * grep -l default: the cheap file list. Unknown or blank values fall back to NAMES — never to
     * FULL, so a typo cannot escalate to the most expensive mode. {@code lines} (also {@code full})
     * asks for the anchored {@code file:line} hits.
     */
    public static ContentLevel resolveGrepLevel(String contentLevel) {
        if (contentLevel == null || contentLevel.isBlank()) {
            return ContentLevel.NAMES;
        }
        String value = contentLevel.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("line") || value.equals("full") || value.equals("snippet")) {
            return ContentLevel.FULL;
        }
        return ContentLevel.NAMES;
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

    /**
     * Reads one symbol. A class FQN additionally returns its first method bodies; the total body
     * budget of one call is {@link ToolResultCaps#READER}.
     */
    public SymbolReadResult readBySignature(
            String method,

            String container,

            String project,

            Boolean includeBodies,

            Integer maxBodies
    ) {
        int bodies = maxBodies == null ? 3 : Math.max(0, Math.min(maxBodies, 10));
        boolean withBodies = includeBodies == null || includeBodies;
        return capSymbolResult(symbolReaderService.readBySignature(method, container, project,
                withBodies, bodies), ToolResultCaps.READER);
    }

    /**
     * Reserve for the truncation hint that {@link ToolResultCaps#cap} appends, so the rendered
     * body still fits the budget it was accounted against.
     */
    private static final int BODY_HINT_RESERVE = 200;

    /** Caps each body so up to 10 matches cannot blow the context window. */
    private SymbolReadResult capSymbolResult(SymbolReadResult result) {
        return capSymbolResult(result, 0);
    }

    /**
     * Caps each body and, when {@code totalBodyBudget > 0}, the sum of all bodies of one call.
     * <p>
     * A per-body cap alone is not enough: ten matches with 10k characters each still reach 100k
     * characters (legacy run: 17.8k per call, 436k in total). Once the budget is spent, further
     * matches keep their signature but lose the body, so the model still sees that they exist
     * and can read them selectively.
     */
    private SymbolReadResult capSymbolResult(SymbolReadResult result, int totalBodyBudget) {
        if (result == null || result.error() != null || result.matches() == null) {
            return result;
        }
        int used = 0;
        List<MethodView> matches = new ArrayList<>(result.matches().size());
        for (MethodView match : result.matches()) {
            String body = match.body();
            if (body == null) {
                matches.add(match);
                continue;
            }
            int allowed = Math.min(ToolResultCaps.READER, body.length());
            if (totalBodyBudget > 0) {
                allowed = Math.min(allowed, Math.max(0, totalBodyBudget - used));
                if (allowed < body.length()) {
                    allowed = Math.max(0, allowed - BODY_HINT_RESERVE);
                }
            }
            if (allowed <= 0) {
                matches.add(new MethodView(match.fqn(), match.file(), match.lineFrom(),
                        match.lineTo(), match.javadoc(), null));
                continue;
            }
            if (allowed < body.length()) {
                matches.add(new MethodView(match.fqn(), match.file(), match.lineFrom(),
                        match.lineTo(), match.javadoc(), ToolResultCaps.cap(body, allowed)));
                used += allowed;
            } else {
                matches.add(match);
                used += body.length();
            }
        }
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
