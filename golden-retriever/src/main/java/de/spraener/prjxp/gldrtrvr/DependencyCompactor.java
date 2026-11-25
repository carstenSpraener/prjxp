package de.spraener.prjxp.gldrtrvr;

import java.util.ArrayList;
import java.util.List;

/**
 * Compacts the dependency section appended to the prompt (see {@code JavaPromptModifier} /
 * {@code TypeScriptRetriever} CLAZZ_FRAME/CLASS_FRAME cases). The dependency chunk format is
 * produced by JavaDependenciesChunker:
 * <pre>
 * ## Dependencies of &lt;fqn&gt;:
 *
 * **Outgoing**
 *   * &lt;fqn1&gt;
 *   * &lt;fqn2&gt;
 *
 * **Incoming**:
 *   * &lt;fqn3&gt;
 * </pre>
 * Entries are lines starting with {@code "  * "} (two spaces, star, space, FQN).
 */
public final class DependencyCompactor {
    private static final String ENTRY_PREFIX = "  * ";
    private static final String MARKER = "\n[%d weitere Dependencies nicht angezeigt]";

    private DependencyCompactor() {
    }

    /**
     * Kürzt die Dependency-Liste auf die ersten {@code maxEntries} Einträge (beide Sektionen
     * Outgoing/Incoming zusammengezählt, Original-Reihenfolge und Sektions-Header bleiben).
     * Am Ende: {@code "\n[<n> weitere Dependencies nicht angezeigt]"}.
     * {@code maxEntries <= 0} oder Content ohne {@code "* "-Einträge → unverändert zurück.
     */
    public static String compact(String dependencyContent, int maxEntries) {
        if (dependencyContent == null || maxEntries <= 0) {
            return dependencyContent;
        }
        String[] lines = dependencyContent.split("\n", -1);
        int totalEntries = 0;
        for (String line : lines) {
            if (line.startsWith(ENTRY_PREFIX)) {
                totalEntries++;
            }
        }
        if (totalEntries <= maxEntries) {
            return dependencyContent;
        }
        List<String> kept = new ArrayList<>(lines.length);
        int keptEntries = 0;
        for (String line : lines) {
            if (line.startsWith(ENTRY_PREFIX)) {
                if (keptEntries < maxEntries) {
                    kept.add(line);
                    keptEntries++;
                }
            } else {
                kept.add(line);
            }
        }
        return String.join("\n", kept) + MARKER.formatted(totalEntries - maxEntries);
    }
}
