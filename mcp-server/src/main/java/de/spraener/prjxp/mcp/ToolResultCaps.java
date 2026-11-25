package de.spraener.prjxp.mcp;

import java.util.Locale;

/**
 * Shared result-size caps (characters) for the prjxp tool surfaces.
 * <p>
 * Both the MCP path ({@link PrjxpMcpTool}) and the hub chat path
 * ({@link de.spraener.prjxp.mcp.tools.PrjxpToolRegistration}) read their caps from here, so a
 * single constant keeps every surface below the model's context budget. The MCP path previously
 * had no caps at all, which is how 17k/20k-character answers reached the model in the benchmark.
 */
public final class ToolResultCaps {

    /** Cap for search results (vectorSearch, grep, readChunks, graph views). */
    public static final int SEARCH = 8_000;
    /** Cap for reader results (readFile content, readBySignature bodies). */
    public static final int READER = 10_000;
    /**
     * Cap for readFile {@code full=true}. The whole-file escape hatch gets more room than a
     * normal read, but is never uncapped: beyond this the model must page with
     * {@code offset}/{@code limit} or read a symbol with {@code readBySignature}.
     */
    public static final int READER_FULL = 20_000;

    /** Approximate characters per token (same 3:1 heuristic as Graphify). */
    public static final int CHARS_PER_TOKEN = 3;

    /** Default token budget for the graph/search tools (Graphify uses the same default). */
    public static final int SEARCH_TOKENS = 2_000;
    /** Hard upper bound for a per-call token budget. */
    public static final int MAX_SEARCH_TOKENS = 4_000;

    private ToolResultCaps() {
    }

    /** Defaults a missing/out-of-range token budget and clamps it to {@code [1, MAX_SEARCH_TOKENS]}. */
    public static int clampTokenBudget(Integer tokenBudget) {
        if (tokenBudget == null || tokenBudget < 1) {
            return SEARCH_TOKENS;
        }
        return Math.min(tokenBudget, MAX_SEARCH_TOKENS);
    }

    /**
     * Caps {@code text} to {@code tokenBudget * CHARS_PER_TOKEN} characters and appends a
     * truncation hint that names the next step. A budget below 1 is treated as 1 token.
     */
    public static String capTokens(String text, int tokenBudget) {
        if (text == null) return "(null)";
        int budget = Math.max(1, tokenBudget);
        int max = budget * CHARS_PER_TOKEN;
        if (text.length() <= max) return text;
        int shown = (max + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        int total = (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
        String hint = String.format(Locale.GERMANY,
                "\n[gekürzt: %,d von %,d Tokens — gezielter mit tokenBudget/depth/offset]",
                shown, total);
        return text.substring(0, max) + hint;
    }

    /**
     * Caps {@code text} to {@code max} characters. When truncated, the cut line names the next
     * step so the model follows the pointer strategy instead of re-reading blindly.
     * {@code max <= 0} disables the cap.
     */
    public static String cap(String text, int max) {
        if (text == null) return "(null)";
        if (max <= 0 || text.length() <= max) return text;
        String hint = String.format(Locale.GERMANY,
                "\n[gekürzt: %,d von %,d Zeichen — gezielter mit readBySignature(FQN#name) oder readFile(offset,limit)]",
                max, text.length());
        return text.substring(0, max) + hint;
    }
}
