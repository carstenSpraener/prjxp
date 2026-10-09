package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolReadResultTest {

    @Test
    void error_createsEmptyMatchesAndRemainingFqns() {
        SymbolReadResult result = SymbolReadResult.error("boom");

        assertThat(result.matches()).isEmpty();
        assertThat(result.remainingFqns()).isEmpty();
        assertThat(result.error()).isEqualTo("boom");
    }

    @Test
    void constructor_withLists() {
        MethodView view = new MethodView("com.example.Foo#bar", "Foo.java", 1, 2, null, "body");
        SymbolReadResult result = new SymbolReadResult(List.of(view), List.of("com.example.Other"), null);

        assertThat(result.matches()).containsExactly(view);
        assertThat(result.remainingFqns()).containsExactly("com.example.Other");
        assertThat(result.error()).isNull();
    }
}
