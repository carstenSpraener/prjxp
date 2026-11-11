package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.DocWindower;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.TestPathFilter;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GrepSearchService {
    public static final String SOURCE = "grep";

    private final PxChunkDaoProvider chunkDaoProvider;
    private final RetrieverRegistry retrieverRegistry;
    private final LanguagePackRegistry languagePackRegistry;
    private final ProjectRegistry projectRegistry;

    /** Max chars a non-code (doc) snippet may have before it is windowed. Initialized with the
     *  property default so that non-Spring construction (tests) behaves like the configured default. */
    @Value("${prjxp.mcp.grep.doc-max-chars:2000}")
    int docMaxChars = 2000;

    /** Half-window size (chars) around the match when windowing a doc snippet. */
    @Value("${prjxp.mcp.grep.doc-window-chars:500}")
    int docWindowChars = 500;

    public List<SearchHit> search(String query, String project, String language, int limit) {
        return search(query, project, language, limit, true);
    }

    public List<SearchHit> search(String query, String project, String language, int limit, boolean includeTests) {
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        String resolved = projectRegistry.resolve(project);
        PxChunkDao dao = chunkDaoProvider.get(resolved).orElse(null);
        if (dao == null) {
            return List.of();
        }

        Map<String, String> filters = buildFilters(language);
        List<ScoredChunk> chunks;
        try {
            chunks = dao.searchFullText(query, filters, limit);
        } catch (UnsupportedOperationException e) {
            return List.of();
        }

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
        SearchParams sp = new SearchParams(limit, 0.85);
        for( String fileName : fileToHitsMap.keySet() ) {
            List<ScoredChunk> scoredChunks = fileToHitsMap.get(fileName);
            StringBuilder sb = new StringBuilder();
            for( var gr : retrieverRegistry.all() ) {
                sb.append(gr.buildPromptForFindings(project, scoredChunks, sp));
            }
            // Doc-chunk windowing (Phase 03): the retriever may rebuild content from the store,
            // so the funnel is the finished snippet. Identity for code (text/x-*), untouched otherwise.
            String mimeType = scoredChunks.isEmpty() ? null : scoredChunks.get(0).chunk().getMimeType();
            String snippet = DocWindower.windowDoc(sb.toString(), query, fileName, mimeType, docMaxChars, docWindowChars);
            double totalScore = scoredChunks.stream().mapToDouble(ScoredChunk::score).sum();
            searchHits.add(
                    new SearchHit(
                            fileName,
                            totalScore,
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
        return searchHits;
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
