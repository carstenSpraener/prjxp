package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.SearchHit;

import java.util.List;

/**
 * Result of a grep search: the (possibly truncated) hits plus the number of files that had
 * matches before the {@code limit} was applied.
 *
 * <p>When more files matched than were returned, {@link #note()} carries a short hint so the
 * caller (typically an LLM) knows it can raise {@code limit} or narrow the query instead of
 * assuming the list is complete. The note is {@code null} when nothing was truncated.</p>
 *
 * <p>Both fields are serialized by the MCP layer, so the note reaches the model verbatim.</p>
 */
public record GrepResult(List<SearchHit> hits, int totalFiles, String note) {

    public GrepResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public boolean truncated() {
        return note != null;
    }

    /** Builds a result and derives the truncation note from the pre-limit file count. */
    public static GrepResult of(List<SearchHit> hits, int totalFiles) {
        List<SearchHit> safeHits = hits == null ? List.of() : hits;
        String note = null;
        if (totalFiles > safeHits.size()) {
            int hidden = totalFiles - safeHits.size();
            note = "Note: showing " + safeHits.size() + " of " + totalFiles
                    + " files with matches; " + hidden
                    + " more not shown. Increase 'limit' or narrow the query.";
        }
        return new GrepResult(safeHits, totalFiles, note);
    }
}
