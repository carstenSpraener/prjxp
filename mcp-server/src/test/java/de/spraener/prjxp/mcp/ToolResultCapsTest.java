package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCapsTest {

    @Test
    void capTokensUsesThreeCharsPerToken() {
        String text = "x".repeat(10_000);

        String capped = ToolResultCaps.capTokens(text, 2_000);

        assertThat(capped).startsWith("x".repeat(6_000));
        assertThat(capped).contains("gekürzt");
        assertThat(capped).contains("Tokens");
    }

    @Test
    void capTokensDoesNotCutShortText() {
        assertThat(ToolResultCaps.capTokens("short", 2_000)).isEqualTo("short");
    }

    @Test
    void nonPositiveBudgetIsTreatedAsOneToken() {
        assertThat(ToolResultCaps.capTokens("abcdef", 0)).startsWith("abc");
        assertThat(ToolResultCaps.capTokens("abcdef", -4)).startsWith("abc");
    }

    @Test
    void clampTokenBudgetDefaultsAndBounds() {
        assertThat(ToolResultCaps.clampTokenBudget(null)).isEqualTo(ToolResultCaps.SEARCH_TOKENS);
        assertThat(ToolResultCaps.clampTokenBudget(0)).isEqualTo(ToolResultCaps.SEARCH_TOKENS);
        assertThat(ToolResultCaps.clampTokenBudget(100)).isEqualTo(100);
        assertThat(ToolResultCaps.clampTokenBudget(99_999)).isEqualTo(ToolResultCaps.MAX_SEARCH_TOKENS);
    }

    @Test
    void existingCharacterCapsAreUnchanged() {
        assertThat(ToolResultCaps.SEARCH).isEqualTo(8_000);
        assertThat(ToolResultCaps.READER).isEqualTo(10_000);
        assertThat(ToolResultCaps.READER_FULL).isEqualTo(20_000);
    }
}
