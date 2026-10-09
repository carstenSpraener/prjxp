package de.spraener.prjxp.common.config;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves placeholders in the form ${ENV_NAME} and ${ENV_NAME:defaultValue}.
 */
public final class EnvPlaceholderResolver {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)(?::([^}]*))?}");

    private EnvPlaceholderResolver() {
    }

    public static String resolve(String value) {
        return resolve(value, key -> {
            String envValue = System.getenv(key);
            if (envValue != null) {
                return envValue;
            }
            return System.getProperty(key);
        });
    }

    static String resolve(String value, Function<String, String> lookup) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String key = matcher.group(1);
            String defaultValue = matcher.group(2);

            String resolved = lookup.apply(key);
            if (resolved == null) {
                resolved = defaultValue;
            }
            if (resolved == null) {
                resolved = matcher.group(0);
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }

        if (!found) {
            return value;
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, String> resolveMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> resolve(e.getValue()),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new
                ));
    }
}
