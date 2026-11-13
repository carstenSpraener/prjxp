package de.spraener.prjxp.common.model;

/**
 * Decides which of two chunks wins when both share the store identity (project, id, part).
 * <p>
 * Chunk ids are not unique across source roots: the same FQN may be chunked from production
 * code and from a test fixture (e.g. {@code src/test/resources/.../LanguagePack.java}). The
 * production copy must win — otherwise the duplicate-safety net would silently drop the real
 * class and surface the fixture instead.
 */
public final class ChunkPathPreference {

    private static final String[] TEST_MARKERS = {
            "/src/test/", "/src/testFixtures/", "/test/", "/testFixtures/",
            "/fixtures/", "/build/", "/out/", "/target/"
    };

    private ChunkPathPreference() {
    }

    /** True when the path looks like test, fixture, or build output. */
    public static boolean isTestPath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String marker : TEST_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code candidate} should replace {@code current} for the same identity. */
    public static boolean prefer(String candidate, String current) {
        return isTestPath(current) && !isTestPath(candidate);
    }
}
