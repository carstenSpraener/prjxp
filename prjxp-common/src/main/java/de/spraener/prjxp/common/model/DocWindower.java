package de.spraener.prjxp.common.model;

import java.util.Locale;

/**
 * Windows <b>document</b> (non-code) content down to a region around the match so that
 * responses for large Markdown/Text chunks (3–8 KB) stay compact.
 * <p>
 * Code chunks (mimeType starting with {@code "text/x-"}) are returned <b>unchanged</b> —
 * full text is a feature for code. This class is the single funnel for doc-chunk
 * windowing in the search paths (grep and vectorSearch); it is applied to the content
 * that the retriever builds.
 */
public final class DocWindower {

    private DocWindower() {
    }

    /**
     * Windows the given content for a document chunk.
     *
     * @param content        the content to window (chunk content / built snippet); may be null
     * @param query          the grep query, used to locate the match (may be null/blank)
     * @param file           the file path, embedded in the truncation marker
     * @param mimeType       the chunk/file mime type; {@code text/x-*} marks code (identity)
     * @param docMaxChars    max characters a doc may have before it is windowed
     * @param docWindowChars half-window size (chars) around the match
     * @return the windowed content, or the input unchanged for code / small docs
     */
    public static String windowDoc(String content, String query, String file, String mimeType,
                                   int docMaxChars, int docWindowChars) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Rule 1: code chunk -> unchanged (full text is a feature)
        if (mimeType != null && mimeType.startsWith("text/x-")) {
            return content;
        }
        // Rule 2: non-code, small enough -> unchanged, no marker
        if (content.length() <= docMaxChars) {
            return content;
        }
        // Rule 3: non-code, too large -> window
        String windowed;
        int idx = indexOfIgnoreCase(content, query);
        if (idx >= 0) {
            windowed = windowAroundMatch(content, idx, query, docWindowChars);
        } else {
            // No literal match (e.g. stemming hit): first docMaxChars chars + ellipsis
            windowed = content.substring(0, Math.min(docMaxChars, content.length())) + " [...]";
        }
        return windowed + "\n[Dokument gekürzt – Bereich um Treffer. Volltext: readFile(\"" + file + "\")]";
    }

    private static String windowAroundMatch(String content, int matchIdx, String query, int docWindowChars) {
        int qLen = (query == null || query.isBlank()) ? 0 : query.trim().length();
        int start = Math.max(0, matchIdx - docWindowChars);
        int end = Math.min(content.length(), matchIdx + qLen + docWindowChars);
        // Align to line boundaries so the window starts/ends on whole lines
        start = alignToLineStart(content, start);
        end = alignToLineEnd(content, end);
        String prefix = start > 0 ? "[...] " : "";
        String suffix = end < content.length() ? " [...]" : "";
        return prefix + content.substring(start, end) + suffix;
    }

    private static int alignToLineStart(String content, int idx) {
        if (idx <= 0) {
            return 0;
        }
        int nl = content.lastIndexOf('\n', idx - 1);
        return nl + 1;
    }

    private static int alignToLineEnd(String content, int idx) {
        if (idx >= content.length()) {
            return content.length();
        }
        int nl = content.indexOf('\n', idx);
        return nl < 0 ? content.length() : nl + 1;
    }

    static int indexOfIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
