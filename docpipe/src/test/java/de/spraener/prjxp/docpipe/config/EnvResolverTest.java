package de.spraener.prjxp.docpipe.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvResolverTest {

    @Test
    void canBeInstantiated() {
        // utility class with static API, but the implicit constructor must be reachable
        assertThat(new EnvResolver()).isNotNull();
    }

    @Test
    void resolve_null_returnsEmptyString() {
        assertThat(EnvResolver.resolve(null)).isEmpty();
    }

    @Test
    void resolve_envPlaceholder_resolvesFromEnvironment() {
        // HOME is set in every sane environment (macOS/Linux/CI)
        String expected = System.getenv("HOME");

        assertThat(EnvResolver.resolve("${HOME}")).isEqualTo(expected);
    }

    @Test
    void resolve_unsetEnvVar_returnsNull() {
        assertThat(EnvResolver.resolve("${PRJXP_TEST_UNSET_VAR_XYZ_123}")).isNull();
    }

    @Test
    void resolve_plainString_returnedUnchanged() {
        assertThat(EnvResolver.resolve("plain-value")).isEqualTo("plain-value");
    }

    @Test
    void resolve_emptyString_returnedUnchanged() {
        assertThat(EnvResolver.resolve("")).isEmpty();
    }

    @Test
    void resolve_placeholderWithoutClosingBrace_stillLooksUpEnvVar() {
        // Document the lenient replace()-based behaviour: "${HOME" -> env var "HOME"
        assertThat(EnvResolver.resolve("${HOME")).isEqualTo(System.getenv("HOME"));
    }

    @Test
    void resolve_nonPlaceholderWithBraces_returnedUnchanged() {
        assertThat(EnvResolver.resolve("value with } brace")).isEqualTo("value with } brace");
    }
}
