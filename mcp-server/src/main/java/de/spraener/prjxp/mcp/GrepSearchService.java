package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GrepSearchService {
    public static final String SOURCE = "grep";

    private static final Map<String, String> LANGUAGE_MIME_TYPES = Map.of(
            "java", "text/x-java-code",
            "ts", "text/x-typescript-code",
            "typescript", "text/x-typescript-code"
    );

    private static final int SNIPPET_CONTEXT = 120;
    private static final int SNIPPET_MAX = 240;

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;
    private final List<GoldenRetriever> retrieverList;

    public List<SearchHit> search(String query, String project, String language, int limit) {
        PxChunkDao dao = chunkDaoProvider.get(resolveProject(project)).orElse(null);
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
        List<SearchHit> result = new ArrayList<>();
        for( var gr : retrieverList ) {
            result.addAll(gr.retrieveSearchHits(resolveProject(project), chunks));
        }
        return result;
    }

    private String resolveProject(String project) {
        if (project == null || project.isBlank() || "default".equalsIgnoreCase(project)) {
            return cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default");
        }
        return project;
    }

    private Map<String, String> buildFilters(String language) {
        Map<String, String> filters = new HashMap<>();
        if (language != null && !language.isBlank()) {
            String normalized = language.trim().toLowerCase(Locale.ROOT);
            filters.put(PxChunk.PXCHUNK_MIME_TYPE, LANGUAGE_MIME_TYPES.getOrDefault(normalized, language.trim()));
        }
        return filters;
    }

    private String extractSnippet(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }

        int idx = indexOfIgnoreCase(content, query.trim());
        if (idx < 0) {
            for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
                int i = indexOfIgnoreCase(content, word);
                if (i >= 0 && (idx < 0 || i < idx)) {
                    idx = i;
                }
            }
        }

        if (idx < 0) {
            return content.length() <= SNIPPET_MAX ? content : content.substring(0, SNIPPET_MAX);
        }

        int start = Math.max(0, idx - SNIPPET_CONTEXT);
        int end = Math.min(content.length(), idx + query.trim().length() + SNIPPET_CONTEXT);
        return content.substring(start, end);
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (needle.isEmpty()) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
