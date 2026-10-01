package de.spraener.prjxp.common.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageCapabilityTest {

    @Test
    void fullConstructor_andAccessors() {
        IdentifierRules rules = new IdentifierRules("^[a-z]+$", true);
        List<SearchParamDef> params = List.of(SearchParamDef.optional("q", "query"));
        LanguageCapability cap = new LanguageCapability(
                "java",
                List.of("text/x-java"),
                List.of("class", "method"),
                params,
                rules);

        assertThat(cap.language()).isEqualTo("java");
        assertThat(cap.mimeTypes()).containsExactly("text/x-java");
        assertThat(cap.symbolTypes()).containsExactly("class", "method");
        assertThat(cap.params()).hasSize(1);
        assertThat(cap.identifierRules().pattern()).isEqualTo("^[a-z]+$");
    }
}
