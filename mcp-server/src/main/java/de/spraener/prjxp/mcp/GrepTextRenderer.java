package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import org.springaicommunity.mcp.method.tool.utils.JsonParser;

/**
 * Renders a {@link GrepResult} for the MCP surface.
 * <p>
 * {@code NAMES}/{@code AGGREGATED} are the cheap "where is X?" modes: the framework promise is one
 * text line per file, not a JSON envelope per hit. The 3.3k-character JSON wrapper for ten files is
 * pure overhead because the useful part is already the {@code "count  path"} snippet.
 * <p>
 * All other levels keep the previous JSON contract.
 */
public final class GrepTextRenderer {

    private GrepTextRenderer() {
    }

    public static String render(GrepResult result, ContentLevel level) {
        return render(result, level, null);
    }

    public static String render(GrepResult result, ContentLevel level, String query) {
        if (result == null) {
            return "";
        }
        if (level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED) {
            return renderNames(result, query);
        }
        return JsonParser.toJson(result);
    }

    static String renderNames(GrepResult result, String query) {
        StringBuilder text = new StringBuilder();
        for (SearchHit hit : result.hits()) {
            String line = hit.snippet();
            if (line == null || line.isBlank()) {
                line = hit.file();
            }
            if (line != null && !line.isBlank()) {
                text.append(line).append('\n');
            }
        }
        if (result.note() != null) {
            text.append(result.note()).append('\n');
        }
        if (text.length() == 0) {
            if (query == null || query.isBlank()) {
                return "No matches.\n";
            }
            // No silent dead end: grep matches whole tokens, so a prefix of a symbol name
            // (e.g. "ChunkRanking" for ChunkRankingService) finds nothing by design.
            return "No matches. Exact token — try the full name or listFiles(\"*" + query + "*\").\n";
        }
        return text.toString();
    }
}
