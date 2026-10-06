package de.spraener.prjxp.common.language;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanguagePacksTest {

    @Test
    void loadExternalFindsTestLanguagePack() {
        List<LanguagePack> external = LanguagePacks.loadExternal();

        assertThat(external).hasSize(1);
        assertThat(external.get(0).language()).isEqualTo("testlang");
    }

    @Test
    void mergeWithEmptySpringPacksIncludesExternal() {
        List<LanguagePack> result = LanguagePacks.merge(List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).language()).isEqualTo("testlang");
    }

    @Test
    void mergeWithConflictingSpringPackExcludesExternal() {
        SpringLanguagePack springPack = new SpringLanguagePack("testlang", "text/x-testlang-spring");
        List<LanguagePack> result = LanguagePacks.merge(List.of(springPack));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(springPack);
    }

    @Test
    void mergeWithNonConflictingSpringPackAppendsExternal() {
        SpringLanguagePack springPack = new SpringLanguagePack("java", "text/x-java-code");
        List<LanguagePack> result = LanguagePacks.merge(List.of(springPack));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(springPack);
        assertThat(result.get(1).language()).isEqualTo("testlang");
    }

    /* --- Test helper --- */

    private static class SpringLanguagePack implements LanguagePack {
        private final String lang;
        private final String mime;

        SpringLanguagePack(String lang, String mime) {
            this.lang = lang;
            this.mime = mime;
        }

        @Override public String language() { return lang; }
        @Override public String mimeType() { return mime; }
    }
}
