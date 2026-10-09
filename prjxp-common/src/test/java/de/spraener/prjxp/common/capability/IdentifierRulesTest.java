package de.spraener.prjxp.common.capability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierRulesTest {

    @Test
    void constructor_andAccessors() {
        IdentifierRules rules = new IdentifierRules("^[a-z]+$", true);

        assertThat(rules.pattern()).isEqualTo("^[a-z]+$");
        assertThat(rules.caseSensitive()).isTrue();
    }
}
