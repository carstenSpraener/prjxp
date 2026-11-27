package de.spraener.prjxp.common.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 03 (FilterBug): deterministic keyword extraction for the lexical fallback.
 * The rules are tuned to the index (StandardAnalyzer: lowercase, no ASCII folding, no stems).
 */
class QueryKeywordsTest {

    @Test
    void extractsEnglishAndGermanStopwords() {
        assertThat(QueryKeywords.extract("is the chunker working", 8))
                .containsExactly("chunker", "working");
        assertThat(QueryKeywords.extract("wie funktioniert der chunker", 8))
                .containsExactly("funktioniert", "chunker");
    }

    @Test
    void splitsCamelCaseAndKeepsIdentifier() {
        assertThat(QueryKeywords.extract("getVorgangService", 8))
                .containsExactly("getvorgangservice", "get", "vorgang", "service");
    }

    @Test
    void keepsUmlautsAndDeduplicates() {
        // No diacritic folding: the index keeps "vorgänge" (StandardAnalyzer without ASCII folding).
        assertThat(QueryKeywords.extract("Vorgänge und Vorgänge", 8))
                .containsExactly("vorgänge");
    }

    @Test
    void capsAtMaxTerms() {
        String question = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda sigma";

        assertThat(QueryKeywords.extract(question, 8))
                .containsExactly("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta");
    }

    @Test
    void zeroMaxTermsUsesTheDefault() {
        String question = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda sigma";

        assertThat(QueryKeywords.extract(question, 0)).hasSize(QueryKeywords.DEFAULT_MAX_TERMS);
    }

    @Test
    void blankQuestionYieldsNoTerms() {
        assertThat(QueryKeywords.extract(null, 8)).isEmpty();
        assertThat(QueryKeywords.extract("   ", 8)).isEmpty();
    }
}
