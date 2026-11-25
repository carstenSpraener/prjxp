package de.spraener.prjxp.mcp.setup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

final class SetupData {
    private SetupData() {}

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> input)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, item) -> {
            if (!(key instanceof String name)) throw invalid();
            result.put(name, copy(item));
        });
        return result;
    }

    static Object copy(Object value) {
        if (value instanceof Map<?, ?>) return map(value);
        if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(SetupData::copy).toList());
        return value;
    }

    static List<String> aliases(String key) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char ch : key.toCharArray()) {
            if (ch == '-') upper = true;
            else {
                camel.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return "api-base-url".equals(key) ? List.of(key, camel.toString(), "apiBaseURL")
                : List.of(key, camel.toString()).stream().distinct().toList();
    }

    static Object get(Map<String, Object> map, String key) {
        Object value = null;
        boolean found = false;
        for (String alias : aliases(key)) {
            if (map.containsKey(alias)) {
                Object next = map.get(alias);
                if (found && !Arrays.equals(canonical(value), canonical(next))) {
                    throw new SetupException("CONFIG_AMBIGUOUS", null, 409, "Conflicting configuration aliases: " + key);
                }
                value = next;
                found = true;
            }
        }
        return value;
    }

    static void put(Map<String, Object> map, String key, Object value) {
        get(map, key);
        aliases(key).forEach(map::remove);
        if (value != null) map.put(key, copy(value));
    }

    static Map<String, Object> section(Map<String, Object> map, String key) {
        Object value = get(map, key);
        if (value == null && aliases(key).stream().noneMatch(map::containsKey)) return new LinkedHashMap<>();
        return map(value);
    }

    static void validate(Map<String, Object> root) {
        Map<String, Object> prjxp = section(root, "prjxp");
        check(prjxp, "setup-completed", "reader-max-output-chars", "chat-models", "embedding-store-lucene", "setup-state");
        check(section(prjxp, "embedding"), "api-base-url", "api-key", "model-name", "max-segments-per-batch",
                "trust-all-certs", "custom-headers", "custom-query-params");
        check(section(prjxp, "hub"), "github-token-key-env", "github-default-pat");
        check(section(prjxp, "embedding-store-lucene"), "vector-dimension", "index-path");
        section(prjxp, "docpipe");
        check(section(section(prjxp, "graph"), "rerank"), "b-graph", "w-base");
        Object models = get(prjxp, "chat-models");
        if (models != null) {
            if (!(models instanceof List<?> list)) throw invalid();
            for (Object model : list) check(map(model), "stereo-type", "server-type", "model-name", "api-key",
                    "provider-url", "timeout-secs", "supports-tools", "body-properties");
        }
        Map<String, Object> state = section(prjxp, "setup-state");
        check(state, "completion-mode", "saved-steps");
        section(state, "tests");
    }

    private static void check(Map<String, Object> map, String... keys) {
        for (String key : keys) get(map, key);
    }

    static byte[] canonical(Object value) {
        try {
            return new ObjectMapper().writeValueAsBytes(ordered(value));
        } catch (JsonProcessingException error) {
            throw invalid();
        }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> input) {
            Map<String, Object> result = new TreeMap<>();
            input.forEach((key, item) -> result.put(Objects.toString(key), ordered(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(SetupData::ordered).toList();
        if (value instanceof Number number) {
            try { return new BigDecimal(number.toString()).stripTrailingZeros(); }
            catch (NumberFormatException invalidNumber) { throw invalid(); }
        }
        return value;
    }

    static SetupException invalid() {
        return new SetupException("INVALID_CONFIG", null, 400, "Configuration must contain valid mappings and values");
    }
}
