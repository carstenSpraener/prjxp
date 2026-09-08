package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.ChunkerSearchCapabilitiesProvider;
import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCapabilitiesRegistryTest {

    private ChunkerSearchCapabilitiesProvider provider(String language, String symbolType) {
        return new ChunkerSearchCapabilitiesProvider() {
            @Override
            public String language() {
                return language;
            }

            @Override
            public LanguageCapability capability() {
                return new LanguageCapability(
                        language,
                        List.of("mime/" + language),
                        List.of(symbolType),
                        List.of(),
                        new IdentifierRules(".+", true));
            }
        };
    }

    @Test
    void collectsAllProviders() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(
                List.of(provider("java", "method"), provider("ts", "function")));

        Map<String, LanguageCapability> byLanguage = registry.byLanguage();

        assertThat(byLanguage).containsOnlyKeys("java", "ts");
    }

    @Test
    void groupsByLanguageKey() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(
                List.of(provider("java", "method")));

        LanguageCapability capability = registry.byLanguage().get("java");

        assertThat(capability.language()).isEqualTo("java");
        assertThat(capability.mimeTypes()).containsExactly("mime/java");
        assertThat(capability.symbolTypes()).containsExactly("method");
    }

    @Test
    void duplicateLanguageIsLastWins() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(
                List.of(provider("java", "first"), provider("java", "second")));

        Map<String, LanguageCapability> byLanguage = registry.byLanguage();

        assertThat(byLanguage).hasSize(1);
        assertThat(byLanguage.get("java").symbolTypes()).containsExactly("second");
    }

    @Test
    void forLanguageIsCaseInsensitive() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(
                List.of(provider("java", "method")));

        assertThat(registry.forLanguage("JAVA")).isPresent();
        assertThat(registry.forLanguage("java").orElseThrow().symbolTypes()).containsExactly("method");
        assertThat(registry.forLanguage("cobol")).isEmpty();
    }

    @Test
    void forLanguageRejectsBlankInput() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(
                List.of(provider("java", "method")));

        assertThat(registry.forLanguage(null)).isEmpty();
        assertThat(registry.forLanguage("   ")).isEmpty();
    }

    @Test
    void emptyProviderListYieldsEmptyMap() {
        SearchCapabilitiesRegistry registry = new SearchCapabilitiesRegistry(List.of());

        assertThat(registry.byLanguage()).isEmpty();
    }
}
