package de.spraener.prjxp.common.model;

/**
 * Shared filter rule for test sources.
 * <p>
 * A project-relative, "/"-separated file path (ReaderSupport.normalizePath convention)
 * is a test path iff it contains "/src/test/" or "/src/testFixtures/"
 * (or starts with "src/test/" / "src/testFixtures/" for paths without a leading segment).
 * Used by the vector-search enrichment (default: exclude tests — semantic noise)
 * and the grep search (default: keep tests — exact search is deliberate intent).
 */
public final class TestPathFilter {

    private TestPathFilter() {
    }

    public static boolean isTestPath(String file) {
        if (file == null || file.isBlank()) {
            return false;
        }
        String normalized = file.replace('\\', '/');
        return normalized.startsWith("src/test/") || normalized.contains("/src/test/")
                || normalized.startsWith("src/testFixtures/") || normalized.contains("/src/testFixtures/");
    }
}
