package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.SearchParamDef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSearchCapabilitiesProviderTest {

    private final JavaSearchCapabilitiesProvider provider = new JavaSearchCapabilitiesProvider();

    @Test
    void languageIsJava() {
        assertThat(provider.language()).isEqualTo("java");
    }

    @Test
    void capabilityMatchesLanguage() {
        assertThat(provider.capability().language()).isEqualTo("java");
    }

    @Test
    void capabilityHasJavaMimeType() {
        assertThat(provider.capability().mimeTypes()).contains("text/x-java-code");
    }

    @Test
    void symbolTypesMatchJavaCodeSections() {
        assertThat(provider.capability().symbolTypes())
                .containsExactly("imports", "methodDoc", "method", "classFrame", "dependenciesInfo");
    }

    @Test
    void paramsCoverByIndexSearch() {
        assertThat(provider.capability().params())
                .extracting(SearchParamDef::name)
                .containsExactly("fqn", "methodName", "signatureHash", "containerFqn", "symbolType");
    }

    @Test
    void allParamsAreOptional() {
        assertThat(provider.capability().params())
                .allSatisfy(param -> assertThat(param.required()).isFalse());
    }

    @Test
    void identifierRulesAcceptJavaIdentifiers() {
        IdentifierRules rules = provider.capability().identifierRules();

        assertThat("myVar_2".matches(rules.pattern())).isTrue();
        assertThat("_private".matches(rules.pattern())).isTrue();
        assertThat("2var".matches(rules.pattern())).isFalse();
        assertThat("with space".matches(rules.pattern())).isFalse();
        assertThat(rules.caseSensitive()).isTrue();
    }
}
