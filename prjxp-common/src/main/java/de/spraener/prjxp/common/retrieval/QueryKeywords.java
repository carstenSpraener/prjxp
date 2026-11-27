package de.spraener.prjxp.common.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic keyword extraction for the lexical fallback (Phase 03, FilterBug): a question is
 * turned into a small list of search terms without any LLM, stemming or synonym expansion.
 * <p>
 * The rules are tuned to the index, not to linguistics: the content field is analysed with
 * {@code StandardAnalyzer} (lowercase, <em>no</em> ASCII folding), so terms are lowercased but
 * diacritics are kept — {@code Vorgänge} stays {@code vorgänge}; an NFD strip would produce
 * {@code vorgange}, which never matches. No stemming either: a stemmer would invent terms the
 * index does not contain.
 */
public final class QueryKeywords {

    /** Default cap for the number of extracted terms. */
    public static final int DEFAULT_MAX_TERMS = 8;

    /**
     * Canonical EN/DE stopword list. Moved here from {@code TfIdfSigner} (lucene-store), which
     * now reuses it, so the signer and the keyword extractor cannot drift apart.
     */
    public static final Set<String> STOPWORDS = new LinkedHashSet<>(List.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with", "from",
            "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those", "it",
            "its", "as", "if", "then", "else", "when", "while", "per", "via", "not", "no", "yes",
            "der", "die", "das", "und", "oder", "von", "zu", "im", "an", "auf", "für", "mit", "bei",
            "nach", "über", "unter", "aus", "ist", "sind", "wird", "werden", "kann", "können", "muss",
            "müssen", "sich", "eine", "einen", "einem", "einer", "dem", "den", "des", "am", "um",
            "als", "auch", "nur", "wie", "was", "wer", "wo", "wann"));

    /**
     * Tokens are split on anything that is not a Unicode letter/digit, '_', '#', '.' or '$'.
     * <p>
     * Note: Java's {@code \p{Alnum}} is ASCII-only (it would split {@code Vorgänge} into
     * {@code vorg} + {@code nge}), so the Unicode classes {@code \p{L}} (letter) and
     * {@code \p{N}} (number) are used instead — diacritics stay inside the token.
     */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_#.$]+");

    /** A Java-like identifier: letter/_/$ first, then word characters/_/$. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    /**
     * CamelCase/PascalCase boundaries: lower/digit to upper ("getVorgang"), and upper to
     * upper+lower ("HTTPServer" → "HTTP", "Server").
     */
    private static final Pattern CAMEL_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private QueryKeywords() {
    }

    /**
     * Extracts search terms from a question: lowercased (diacritics kept), camelCase-split
     * (the full identifier is kept as well when it looks like one), stopword-free, length
     * &gt;= 3, deduplicated in first-seen order, capped at {@code maxTerms}.
     *
     * @param question the user question
     * @param maxTerms cap for the number of terms; {@code <= 0} uses {@link #DEFAULT_MAX_TERMS}
     */
    public static List<String> extract(String question, int maxTerms) {
        int cap = maxTerms <= 0 ? DEFAULT_MAX_TERMS : maxTerms;
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(question)) {
            if (token.isEmpty()) {
                continue;
            }
            if (isIdentifier(token)) {
                // The full identifier first (the index keeps it as one term), then the parts.
                addTerm(terms, token);
                for (String part : CAMEL_BOUNDARY.split(token)) {
                    addTerm(terms, part);
                }
            } else {
                addTerm(terms, token);
            }
        }
        List<String> result = new ArrayList<>(Math.min(cap, terms.size()));
        for (String term : terms) {
            if (result.size() >= cap) {
                break;
            }
            result.add(term);
        }
        return result;
    }

    /** {@code [A-Za-z_$][\w$]*} that contains '_', '$' or an uppercase letter. */
    private static boolean isIdentifier(String token) {
        if (!IDENTIFIER.matcher(token).matches()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '_' || c == '$' || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    /** Lowercases (no diacritic folding) and keeps the term when it is significant. */
    private static void addTerm(Set<String> terms, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.length() >= 3 && !STOPWORDS.contains(lower)) {
            terms.add(lower);
        }
    }
}
