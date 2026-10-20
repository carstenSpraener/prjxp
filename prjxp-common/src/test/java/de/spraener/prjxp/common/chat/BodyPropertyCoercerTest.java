package de.spraener.prjxp.common.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BodyPropertyCoercerTest {

    @Test
    void coerce_nullReturnsEmptyMap() {
        assertThat(BodyPropertyCoercer.coerce(null)).isEmpty();
    }

    @Test
    void coerce_emptyMapReturnsEmptyMap() {
        assertThat(BodyPropertyCoercer.coerce(Map.of())).isEmpty();
    }

    @Test
    void coerce_nativeBooleanPassesThrough() {
        Map<String, Object> result = BodyPropertyCoercer.coerce(Map.of("enable_thinking", false));

        assertThat(result).containsEntry("enable_thinking", false);
        assertThat(result.get("enable_thinking")).isInstanceOf(Boolean.class);
    }

    @Test
    void coerce_stringFalseBecomesBoolean() {
        // Simulates a value that arrived as a String (e.g. from an env var or quoted YAML).
        Map<String, Object> result = BodyPropertyCoercer.coerce(Map.of("enable_thinking", "false"));

        assertThat(result.get("enable_thinking")).isEqualTo(false);
        assertThat(result.get("enable_thinking")).isInstanceOf(Boolean.class);
    }

    @Test
    void coerce_stringTrueBecomesBoolean() {
        Map<String, Object> result = BodyPropertyCoercer.coerce(Map.of("flag", "true"));

        assertThat(result.get("flag")).isEqualTo(true);
        assertThat(result.get("flag")).isInstanceOf(Boolean.class);
    }

    @Test
    void coerce_stringNumberBecomesNumber() {
        Map<String, Object> result = BodyPropertyCoercer.coerce(Map.of("top_p", "0.7", "max", "42"));

        assertThat(result.get("top_p")).isEqualTo(0.7);
        assertThat(result.get("max")).isEqualTo(42);
    }

    @Test
    void coerce_plainStringStaysString() {
        Map<String, Object> result = BodyPropertyCoercer.coerce(Map.of("user", "casi"));

        assertThat(result.get("user")).isEqualTo("casi");
        assertThat(result.get("user")).isInstanceOf(String.class);
    }

    @Test
    void coerce_mixedValuesCoercesEach() {
        Map<String, Object> result = BodyPropertyCoercer.coerce(
                Map.of("enable_thinking", "false", "temperature", 0.2, "name", "qwen"));

        assertThat(result.get("enable_thinking")).isEqualTo(false);
        assertThat(result.get("temperature")).isEqualTo(0.2);
        assertThat(result.get("name")).isEqualTo("qwen");
    }

    @Test
    void coerce_doesNotMutateInput() {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("enable_thinking", "false");

        BodyPropertyCoercer.coerce(input);

        // The original (raw) value is preserved; coercion happens on the copy.
        assertThat(input.get("enable_thinking")).isEqualTo("false");
    }
}
