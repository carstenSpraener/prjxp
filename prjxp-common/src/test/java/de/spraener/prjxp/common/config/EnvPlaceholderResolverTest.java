package de.spraener.prjxp.common.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvPlaceholderResolverTest {

    @Test
    void resolve_replacesSinglePlaceholder() {
        String result = EnvPlaceholderResolver.resolve("${API_KEY}", key -> Map.of("API_KEY", "secret").get(key));
        assertThat(result).isEqualTo("secret");
    }

    @Test
    void resolve_usesDefaultWhenMissing() {
        String result = EnvPlaceholderResolver.resolve("${API_KEY:default-value}", key -> null);
        assertThat(result).isEqualTo("default-value");
    }

    @Test
    void resolve_replacesMultiplePlaceholdersInsideText() {
        String result = EnvPlaceholderResolver.resolve(
                "https://${HOST}:${PORT}/v1",
                key -> Map.of("HOST", "localhost", "PORT", "1234").get(key)
        );
        assertThat(result).isEqualTo("https://localhost:1234/v1");
    }

    @Test
    void resolve_keepsPlaceholderWhenUnresolvedAndNoDefault() {
        String result = EnvPlaceholderResolver.resolve("${MISSING}", key -> null);
        assertThat(result).isEqualTo("${MISSING}");
    }
}
