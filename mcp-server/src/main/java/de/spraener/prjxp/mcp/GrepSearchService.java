package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.DocWindower;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.TestPathFilter;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.retrieval.ranking.RankFusion;
import de.spraener.prjxp.common.retrieval.ranking.RankedFile;
import de.spraener.prjxp.common.retrieval.ranking.RankedItem;
import de.spraener.prjxp.common.retrieval.ranking.WeightedSignal;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.mcp.ranking.StructureSignal;
import de.spraener.prjxp.mcp.rest.QueryStatsCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GrepSearchService {
    public static final String SOURCE = "grep";

    private final PxChunkDaoProvider chunkDaoProvider;
    private final RetrieverRegistry retrieverRegistry;
    private final LanguagePackRegistry languagePackRegistry;
    private final ProjectRegistry projectRegistry;
    private final QueryStatsCollector queryStats;

    /** Max chars a non-code (doc) snippet may have before it is windowed. Initialized with the
     *  property default so that non-Spring construction (tests) behaves like the configured default. */
    @Value("${prjxp.mcp.grep.doc-max-chars:2000}")
    int docMaxChars = 2000;

    /** Half-window size (chars) around the match when windowing a doc snippet. */
    @Value("${prjxp.mcp.grep.doc-window-chars:500}")
    int docWindowChars = 500;

    /** Raw-chunk window cap for the overscan loop (SearchLimits.MAX_LIMIT * 5). */
    private static final int MAX_WINDOW = 500;

    /** {@code prjxp.ranking.grep=legacy} restores the pre-fusion hit-count order (ablation). */
    static final String RANKING_LEGACY = "legacy";

    /** {@code fusion} (default) or {@code legacy}; read per call so a benchmark can toggle it. */
    @Value("${prjxp.ranking.grep:fusion}")
    String rankingMode = "fusion";

    /** Rank-fusion weights of the grep signals. */
    @Value("${prjxp.ranking.weight.name:1.0}")
    double nameWeight = 1.0;

    @Value("${prjxp.ranking.weight.lexical:1.0}")
    double lexicalWeight = 1.0;

    @Value("${prjxp.ranking.weight.structure:1.0}")
    double structureWeight = 1.0;

    /** How often the pattern occurs in a file. A token search is about frequency, too. */
    @Value("${prjxp.ranking.weight.frequency:1.0}")
    double frequencyWeight = 1.0;

    /** Optional: absent in slice tests and in deployments without the retriever stack. */
    @Autowired(required = false)
    ChunkRankingService chunkRankingService;

    public List<SearchHit> search(String query, String project, String language, int limit) {
        return search(query, project, language, limit, true);
    }

    public List<SearchHit> search(String query, String project, String language, int limit, boolean includeTests) {
        return search(query, project, language, limit, includeTests, ContentLevel.FULL);
    }

    public List<SearchHit> search(String query, String project, String language, int limit, boolean includeTests, ContentLevel contentLevel) {
        return searchDetailed(query, project, language, limit, includeTests, contentLevel).hits();
    }

    /**
     * Like {@link #search}, but keeps the pre-limit file count so the caller can tell whether the
     * result was truncated (see {@link GrepResult#note()}).
     */
    public GrepResult searchDetailed(String query, String project, String language, int limit, boolean includeTests, ContentLevel contentLevel) {
        long startedAt = System.nanoTime();
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        String resolved = projectRegistry.resolve(project);
        PxChunkDao dao = chunkDaoProvider.get(resolved).orElse(null);
        if (dao == null) {
            return GrepResult.of(List.of(), 0);
        }

        Map<String, String> filters = buildFilters(language);
        // limit = max returned FILES, applied after grouping + test filter;
        // overscan a larger raw window so filtering cannot starve the result.
        int window = Math.min(Math.max(limit * 4, 40), MAX_WINDOW);
        while (true) {
            List<ScoredChunk> chunks;
            try {
                chunks = dao.searchFullText(query, filters, window);
            } catch (UnsupportedOperationException e) {
                return GrepResult.of(List.of(), 0);
            }
            GrepResult result = toHits(project, query, chunks, limit, includeTests, contentLevel);
            if (includeTests || result.hits().size() >= limit || chunks.size() < window || window >= MAX_WINDOW) {
                queryStats.recordQuery(query, elapsedMs(startedAt), result.hits().size(), false);
                return result;
            }
            window = Math.min(window * 2, MAX_WINDOW);
        }
    }

    /**
     * Line-oriented search: one hit per matching line, anchored at {@code file:line}. Candidates
     * come from the analyzed index instead of a full scan; the line check decides what is a real
     * hit, the fusion decides the order. {@code REGEX} keeps the scan because a line regular
     * expression cannot be pre-filtered by an index query.
     */
    public GrepResult searchMatches(String query, String project, String language, int limit,
                                    boolean includeTests, GrepMode mode) {
        return rankedSearch(query, project, language, limit, includeTests, mode, false);
    }

    /**
     * grep -l: one entry per file that contains the pattern. The limit counts FILES, applied after
     * the fused ranking, so a heavily matching file cannot crowd the others out. The hits carry
     * neither line numbers nor snippets — read the file for content.
     */
    public GrepResult searchFiles(String query, String project, String language, int limit,
                                  boolean includeTests, GrepMode mode) {
        return rankedSearch(query, project, language, limit, includeTests, mode, true);
    }

    /**
     * Keyword search for the vector-search fallback (Phase 03): the terms are OR-ed in the index
     * ({@code searchAnyTerms}) instead of AND-ed, so a single keyword of a long question is
     * enough to surface a chunk. The line check is case-insensitive — the existing
     * {@code compilePattern(SUBSTRING)} is case-sensitive and would not find {@code pxchunk} in
     * {@code PxChunk}. {@code maxFiles} limits the number of distinct files; the lines within a
     * file are kept. No match yields a note with the searched terms, not an abort string.
     *
     * @param extraFilters additional filters (e.g. the sourceType mime filter); they are added
     *                     without overriding the language mime already set by {@code language}
     */
    public GrepResult searchKeywords(List<String> terms, String project, String language,
                                     int maxFiles, boolean includeTests, Map<String, String> extraFilters) {
        long startedAt = System.nanoTime();
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        String resolved = projectRegistry.resolve(project);
        PxChunkDao dao = chunkDaoProvider.get(resolved).orElse(null);
        if (dao == null) {
            return GrepResult.of(List.of(), 0);
        }
        List<String> clean = terms == null ? List.of() : terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .toList();
        if (clean.isEmpty()) {
            return GrepResult.of(List.of(), 0);
        }

        Map<String, String> filters = buildFilters(language);
        if (extraFilters != null) {
            for (Map.Entry<String, String> entry : extraFilters.entrySet()) {
                filters.putIfAbsent(entry.getKey(), entry.getValue());   // the language mime wins
            }
        }

        List<ScoredChunk> candidates;
        try {
            candidates = dao.searchAnyTerms(clean, filters, 0);   // 0 = every match, like grep
        } catch (UnsupportedOperationException e) {
            return GrepResult.of(List.of(), 0);
        }

        String mime = languageMime(language);
        List<PxChunk> source = candidates.stream()
                .map(ScoredChunk::chunk)
                .filter(chunk -> isScannable(chunk, mime, includeTests))
                .toList();

        // One pattern: the quoted terms as an alternation. Case-insensitive, because camelCase
        // identifiers are capitalised in code ("pxchunk" must find "PxChunk").
        String alternation = clean.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile(alternation, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        List<PxChunk> matched = new ArrayList<>();
        for (PxChunk chunk : source) {
            if (hasMatchingLine(chunk, pattern)) {
                matched.add(chunk);
            }
        }
        if (matched.isEmpty()) {
            queryStats.recordQuery(String.join(" ", clean), elapsedMs(startedAt), 0, false);   // a query without hits is a query
            return new GrepResult(List.of(), 0,
                    "Keine Keyword-Treffer für: " + String.join(", ", clean));
        }

        List<RankedFile> order = fileOrder(dao, String.join(" ", clean), matched, includeTests);
        Map<String, Integer> fileRank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            fileRank.putIfAbsent(order.get(i).file(), i);
        }
        List<SearchHit> all = collectLineHits(matched, pattern);
        all.sort(Comparator.comparingInt((SearchHit hit) -> fileRank.getOrDefault(hit.file(), Integer.MAX_VALUE))
                .thenComparing(SearchHit::lineFrom)
                .thenComparing(SearchHit::chunkId));

        // maxFiles limits the number of distinct files; the lines within a file are kept.
        int fileLimit = Math.max(1, maxFiles);
        List<SearchHit> hits = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (SearchHit hit : all) {
            boolean newFile = files.add(hit.file());
            if (newFile && files.size() > fileLimit) {
                break;
            }
            hits.add(hit);
        }
        int totalFiles = (int) all.stream().map(SearchHit::file).distinct().count();
        String note = hits.size() < all.size()
                ? "Note: showing " + hits.size() + " of " + all.size()
                + " hits; raise 'limit' or narrow the query."
                : null;
        queryStats.recordQuery(String.join(" ", clean), elapsedMs(startedAt), hits.size(), false);
        return new GrepResult(hits, totalFiles, note);
    }

    private GrepResult rankedSearch(String query, String project, String language, int limit,
                                    boolean includeTests, GrepMode mode, boolean filesOnly) {
        long startedAt = System.nanoTime();
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        if (query == null || query.isBlank()) {
            return new GrepResult(List.of(), 0, "ERROR: 'query' is required.");
        }
        String resolved = projectRegistry.resolve(project);
        PxChunkDao dao = chunkDaoProvider.get(resolved).orElse(null);
        if (dao == null) {
            return GrepResult.of(List.of(), 0);
        }

        Pattern pattern;
        try {
            pattern = compilePattern(query, mode);
        } catch (PatternSyntaxException e) {
            return new GrepResult(List.of(), 0,
                    "ERROR: invalid regular expression: " + e.getDescription());
        }
        // grep must be complete: a ranked window would drop files whose chunks score low, so the
        // candidate source is asked for every match (grep -l is the "does this occur?" tool).
        List<PxChunk> matched = matchingChunks(dao, query, pattern, language, includeTests, mode);
        if (matched.isEmpty()) {
            queryStats.recordQuery(query, elapsedMs(startedAt), 0, false);   // a query without hits is a query
            return GrepResult.of(List.of(), 0);
        }
        List<RankedFile> fileOrder = fileOrder(dao, query, matched, includeTests);

        if (filesOnly) {
            List<SearchHit> files = new ArrayList<>();
            for (RankedFile file : fileOrder) {
                if (files.size() >= limit) {
                    break;
                }
                files.add(new SearchHit("", 0.0, file.file(), null, null, null, SOURCE, Map.of()));
            }
            GrepResult result = new GrepResult(files, fileOrder.size(),
                    filesNote(files.size(), fileOrder.size()));
            queryStats.recordQuery(query, elapsedMs(startedAt), result.hits().size(), false);
            return result;
        }

        Map<String, Integer> fileRank = new HashMap<>();
        for (int i = 0; i < fileOrder.size(); i++) {
            fileRank.putIfAbsent(fileOrder.get(i).file(), i);
        }
        List<SearchHit> all = collectLineHits(matched, pattern);
        all.sort(Comparator.comparingInt((SearchHit hit) -> fileRank.getOrDefault(hit.file(), Integer.MAX_VALUE))
                .thenComparing(SearchHit::lineFrom)
                .thenComparing(SearchHit::chunkId));
        List<SearchHit> hits = all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
        String note = all.size() > hits.size()
                ? "Note: showing " + hits.size() + " of " + all.size()
                + " hits; raise 'limit' or narrow the query."
                : null;
        GrepResult result = new GrepResult(hits, all.size(), note);
        queryStats.recordQuery(query, elapsedMs(startedAt), result.hits().size(), false);
        return result;
    }

    /**
     * Chunks that contain at least one matching, anchorable line — ordered as the candidate source
     * delivered them (BM25 for the index path, index order for the scan).
     */
    private List<PxChunk> matchingChunks(PxChunkDao dao, String query, Pattern pattern, String language,
                                         boolean includeTests, GrepMode mode) {
        String mime = languageMime(language);
        List<PxChunk> source;
        if (mode == GrepMode.REGEX) {
            try (Stream<PxChunk> chunks = dao.findAll()) {
                source = chunks.filter(chunk -> isScannable(chunk, mime, includeTests)).toList();
            }
        } else {
            List<ScoredChunk> candidates;
            try {
                candidates = dao.searchCandidates(query, buildFilters(language), 0);
            } catch (UnsupportedOperationException e) {
                return List.of();
            }
            source = candidates.stream()
                    .map(ScoredChunk::chunk)
                    .filter(chunk -> isScannable(chunk, mime, includeTests))
                    .toList();
        }
        List<PxChunk> matched = new ArrayList<>();
        for (PxChunk chunk : source) {
            if (hasMatchingLine(chunk, pattern)) {
                matched.add(chunk);
            }
        }
        return matched;
    }

    private static boolean hasMatchingLine(PxChunk chunk, Pattern pattern) {
        int baseLine = parseLine(chunk.getFromLine());
        String[] lines = (chunk.getContent() == null ? "" : chunk.getContent()).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find() && isAnchorableLine(chunk, baseLine + i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One hit per (file, line): chunk overlap repeats lines, and an overlap chunk may start in the
     * middle of a line — the longer snippet wins.
     */
    static List<SearchHit> collectLineHits(List<PxChunk> chunks, Pattern pattern) {
        Map<String, SearchHit> byFileLine = new LinkedHashMap<>();
        for (PxChunk chunk : chunks) {
            int baseLine = parseLine(chunk.getFromLine());
            String[] lines = (chunk.getContent() == null ? "" : chunk.getContent()).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!pattern.matcher(lines[i]).find()) {
                    continue;
                }
                int line = baseLine + i;
                if (!isAnchorableLine(chunk, line)) {
                    continue;   // synthetic content (e.g. class-frame signature block)
                }
                SearchHit hit = new SearchHit(chunk.getId(), 0.0, chunk.getFile(), line, line,
                        snippet(lines[i]), SOURCE, Map.of());
                byFileLine.merge(chunk.getFile() + ":" + line, hit, GrepSearchService::longerSnippet);
            }
        }
        return new ArrayList<>(byFileLine.values());
    }

    /**
     * The fused file order: lexical (candidate order), structure (code section) and name (file
     * name/path contains the query). {@code prjxp.ranking.grep=legacy} restores the old hit-count
     * ordering for ablation runs.
     */
    List<RankedFile> fileOrder(PxChunkDao dao, String query, List<PxChunk> matched,
                                       boolean includeTests) {
        if (RANKING_LEGACY.equalsIgnoreCase(rankingMode)) {
            return legacyFileOrder(matched);
        }
        List<WeightedSignal> signals = new ArrayList<>();
        List<RankedItem> lexical = new ArrayList<>(matched.size());
        for (PxChunk chunk : matched) {
            lexical.add(new RankedItem(chunk.getId(), chunk.getFile(), 0.0));
        }
        signals.add(new WeightedSignal("lexical", lexicalWeight, lexical));
        List<RankedItem> structured = structureSignal().rank(matched);
        if (!structured.isEmpty()) {
            signals.add(new WeightedSignal("structure", structureWeight, structured));
        }
        List<RankedItem> byFrequency = frequencySignal(matched);
        if (!byFrequency.isEmpty()) {
            signals.add(new WeightedSignal("frequency", frequencyWeight, byFrequency));
        }
        List<RankedItem> names = nameSignal(dao, query, matched, includeTests);
        if (!names.isEmpty()) {
            signals.add(new WeightedSignal("name", nameWeight, names));
        }
        return exactNameFirst(RankFusion.DEFAULT.rankFiles(signals, Integer.MAX_VALUE), query);
    }

    /**
     * A file whose name *is* the query (basename without extension, case-insensitive) comes first.
     * Rank fusion cannot express that: an exact match and a test file that merely contains the
     * name differ by one rank position, and a few extra hits outweigh that. Everything else stays
     * in fused order.
     */
    private static List<RankedFile> exactNameFirst(List<RankedFile> fused, String query) {
        String token = nameToken(query);
        if (token == null || fused.size() < 2) {
            return fused;
        }
        List<RankedFile> exact = new ArrayList<>();
        List<RankedFile> rest = new ArrayList<>();
        for (RankedFile file : fused) {
            if (isExactNameMatch(file.file(), token)) {
                exact.add(file);
            } else {
                rest.add(file);
            }
        }
        if (exact.isEmpty()) {
            return fused;
        }
        exact.addAll(rest);
        return exact;
    }

    private static boolean isExactNameMatch(String file, String token) {
        if (file == null) {
            return false;
        }
        String base = file.substring(file.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.equalsIgnoreCase(token);
    }

    /** Files ordered by how many chunks matched; one entry per file, so RRF sees the count. */
    private static List<RankedItem> frequencySignal(List<PxChunk> matched) {
        Map<String, Integer> countByFile = new LinkedHashMap<>();
        for (PxChunk chunk : matched) {
            if (chunk.getFile() != null) {
                countByFile.merge(chunk.getFile(), 1, Integer::sum);
            }
        }
        List<String> files = new ArrayList<>(countByFile.keySet());
        files.sort(Comparator.comparingInt((String file) -> countByFile.getOrDefault(file, 0)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<RankedItem> items = new ArrayList<>(files.size());
        for (String file : files) {
            items.add(new RankedItem(file, file, countByFile.get(file)));
        }
        return items;
    }

    private StructureSignal structureSignal() {
        return new StructureSignal(chunkRankingService);
    }

    /** Pre-fusion order: most matching chunks first, ties alphabetically (ablation baseline). */
    private static List<RankedFile> legacyFileOrder(List<PxChunk> matched) {
        Map<String, Integer> countByFile = new LinkedHashMap<>();
        for (PxChunk chunk : matched) {
            countByFile.merge(chunk.getFile(), 1, Integer::sum);
        }
        List<String> files = new ArrayList<>(countByFile.keySet());
        files.sort(Comparator.comparingInt((String file) -> countByFile.getOrDefault(file, 0)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<RankedFile> result = new ArrayList<>(files.size());
        for (String file : files) {
            result.add(new RankedFile(file, countByFile.get(file), countByFile.get(file)));
        }
        return result;
    }

    /**
     * Files whose name or path contains the first word of the query — the strongest intent
     * signal. Only files the line check confirmed: a name says what a file is <em>about</em>, not
     * that it contains the pattern, and {@code grep -l} must never list a file without a match.
     */
    private List<RankedItem> nameSignal(PxChunkDao dao, String query, List<PxChunk> matched,
                                        boolean includeTests) {
        String token = nameToken(query);
        if (token == null) {
            return List.of();
        }
        Set<String> verified = new HashSet<>();
        for (PxChunk chunk : matched) {
            if (chunk.getFile() != null) {
                verified.add(chunk.getFile());
            }
        }
        List<String> files;
        try {
            files = dao.findFilesContaining(token, MAX_WINDOW);
        } catch (UnsupportedOperationException e) {
            return List.of();
        }
        List<RankedItem> items = new ArrayList<>(files.size());
        for (String file : files) {
            if (!verified.contains(file)) {
                continue;   // name match without a matching line: not a hit
            }
            if (!includeTests && TestPathFilter.isTestPath(file)) {
                continue;
            }
            items.add(new RankedItem(file, file, 0.0));
        }
        return items;
    }

    private static String nameToken(String query) {
        for (String raw : query.trim().split("\\s+")) {
            String token = raw.replaceAll("[^\\p{L}\\p{N}_.$]", "");
            if (token.length() >= 3) {
                return token;
            }
        }
        return null;
    }

    private static String filesNote(int shown, int total) {
        if (total <= shown) {
            return null;
        }
        return "Note: showing " + shown + " of " + total
                + " files with matches; raise 'limit' or narrow the query.";
    }

    private static Pattern compilePattern(String query, GrepMode mode) {
        return switch (mode == null ? GrepMode.SUBSTRING : mode) {
            case EXACT -> Pattern.compile("(?<![\\w$])" + Pattern.quote(query) + "(?![\\w$])");
            case SUBSTRING -> Pattern.compile(Pattern.quote(query));
            case REGEX -> Pattern.compile(query);
        };
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static boolean isScannable(PxChunk chunk, String mime, boolean includeTests) {
        if (chunk == null || chunk.getFile() == null || chunk.getFile().isBlank()) {
            return false;
        }
        if (PxChunk.FULLTEXT_MARKER.equals(chunk.getMetadata().get(PxChunk.PXCHUNK_TYPE))) {
            return false;
        }
        if (!includeTests && TestPathFilter.isTestPath(chunk.getFile())) {
            return false;
        }
        return mime == null || mime.equalsIgnoreCase(chunk.getMimeType());
    }

    private String languageMime(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if ("ts".equals(normalized)) {
            normalized = "typescript";
        }
        return mimeByLanguage().getOrDefault(normalized, language.trim());
    }

    /**
     * A chunk may be only partly verbatim source (class frames append a synthetic signature
     * block). Lines outside the declared verbatim range have no valid {@code file:line} anchor.
     */
    private static boolean isAnchorableLine(PxChunk chunk, int line) {
        String range = chunk.getMetadata().get(PxChunk.PXCHUNK_VERBATIM_LINES);
        if (range == null || range.isBlank()) {
            return true;
        }
        int dash = range.indexOf('-');
        if (dash <= 0) {
            return true;
        }
        try {
            int from = Integer.parseInt(range.substring(0, dash).trim());
            int to = Integer.parseInt(range.substring(dash + 1).trim());
            return line >= from && line <= to;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static int parseLine(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String snippet(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.length() <= SearchHit.SNIPPET_MAX
                ? trimmed
                : trimmed.substring(0, SearchHit.SNIPPET_MAX);
    }

    /** Keeps the more complete snippet when overlapping chunks report the same source line. */
    private static SearchHit longerSnippet(SearchHit existing, SearchHit candidate) {
        return candidate.snippet().length() > existing.snippet().length() ? candidate : existing;
    }

    private GrepResult toHits(String project, String query, List<ScoredChunk> chunks, int limit, boolean includeTests, ContentLevel contentLevel) {
        Map<String,List<ScoredChunk>> fileToHitsMap = new HashMap<>();
        for( ScoredChunk sChunk : chunks) {
            String fileName = sChunk.chunk().getFile();
            if( fileName == null ) {
                continue;
            }
            List<ScoredChunk> hits = fileToHitsMap.get(fileName);
            if( hits == null ) {
                hits = new ArrayList<>();
                fileToHitsMap.put(fileName, hits);
            }
            hits.add(sChunk);
        }

        List<SearchHit> searchHits = new ArrayList<>();
        ContentLevel level = contentLevel == null ? ContentLevel.FULL : contentLevel;
        boolean namesMode = level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED;
        SearchParams sp = new SearchParams(limit, 0.85);
        sp.setContentLevel(level);
        for( String fileName : fileToHitsMap.keySet() ) {
            List<ScoredChunk> scoredChunks = fileToHitsMap.get(fileName);
            int hitCount = scoredChunks.size();
            String snippet;
            if (namesMode) {
                // grep -l: one line per file with the hit-count prefix, no content, no windowing.
                // Efficiency measure 5: AGGREGATED is an alias for NAMES in search tools.
                snippet = hitCount + "  " + fileName;
            } else {
                StringBuilder sb = new StringBuilder();
                for( var gr : retrieverRegistry.all() ) {
                    sb.append(gr.buildPromptForFindings(project, scoredChunks, sp));
                }
                // Doc-chunk windowing (Phase 03): the retriever may rebuild content from the store,
                // so the funnel is the finished snippet. Identity for code (text/x-*), untouched otherwise.
                String mimeType = scoredChunks.isEmpty() ? null : scoredChunks.get(0).chunk().getMimeType();
                snippet = DocWindower.windowDoc(sb.toString(), query, fileName, mimeType, docMaxChars, docWindowChars);
            }
            double totalScore = scoredChunks.stream().mapToDouble(ScoredChunk::score).sum();
            // grep -l (NAMES/AGGREGATED) ranks by hit count; other levels rank by total score.
            double sortKey = namesMode ? hitCount : totalScore;
            searchHits.add(
                    new SearchHit(
                            fileName,
                            sortKey,
                            fileName,
                            null,
                            null,
                            snippet,
                            SOURCE,
                            Map.of()
                    )
            );
        }
        if (!includeTests) {
            searchHits.removeIf(h -> TestPathFilter.isTestPath(h.file()));
        }
        searchHits.sort((a, b) -> Double.compare(b.score(), a.score()));
        int totalFiles = searchHits.size();
        List<SearchHit> limited = totalFiles > limit
                ? new ArrayList<>(searchHits.subList(0, limit))
                : searchHits;
        return GrepResult.of(limited, totalFiles);
    }

    private Map<String, String> buildFilters(String language) {
        Map<String, String> filters = new HashMap<>();
        if (language != null && !language.isBlank()) {
            String normalized = language.trim().toLowerCase(Locale.ROOT);
            if ("ts".equals(normalized)) {
                normalized = "typescript";   // Alias, wie bisher
            }
            filters.put(PxChunk.PXCHUNK_MIME_TYPE, mimeByLanguage().getOrDefault(normalized, language.trim()));
        }
        // FULLTEXT copies exist for lossless readFile only — never grep hits (raw Lucene field name)
        filters.put(PxChunk.PXCHUNK_METADATA + "." + PxChunk.PXCHUNK_TYPE, "!" + PxChunk.FULLTEXT_MARKER);
        return filters;
    }

    /** Sprache -> MIME aus der gemeinsamen LanguagePackRegistry (Built-in-Packs + Plugins). */
    private Map<String, String> mimeByLanguage() {
        return languagePackRegistry.mimeByLanguage();
    }
}
