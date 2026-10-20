package de.spraener.prjxp.common.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coerces {@code bodyProperties} values so the JSON request body carries native JSON types rather
 * than strings.
 * <p>
 * This matters because the same logical value can reach the supplier as a native type (when bound
 * from YAML, e.g. {@code enable_thinking: false} → {@link Boolean#FALSE}) or as a String (when it
 * comes from an environment variable or is quoted in YAML, e.g. {@code "false"}). Without coercion
 * the string {@code "false"} would be serialized as the JSON string {@code "false"} instead of the
 * boolean {@code false}, which providers such as Qwen3 do not interpret as "off".
 * <p>
 * Non-string values (already {@link Boolean}/{@link Number}/etc.) pass through unchanged.
 */
public final class BodyPropertyCoercer {

    private BodyPropertyCoercer() {
    }

    /**
     * Returns a copy of {@code source} with each value coerced to its native JSON type.
     *
     * @param source the raw body properties (may be {@code null})
     * @return an empty map when {@code source} is {@code null} or empty; otherwise a new map with
     * coerced values, preserving entry order
     */
    public static Map<String, Object> coerce(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), coerceValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Coerces a single value: string representations of booleans and numbers become their native
     * types; everything else (including other strings) is returned unchanged.
     */
    static Object coerceValue(Object value) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                // not an integer — try double below
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                // not a number — keep the original string
            }
            return s;
        }
        return value;
    }
}
