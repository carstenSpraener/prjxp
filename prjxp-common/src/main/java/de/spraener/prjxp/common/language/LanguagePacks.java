package de.spraener.prjxp.common.language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/** Discovery + merge helper for {@link LanguagePack} instances. */
public final class LanguagePacks {

    private static final Logger log = LoggerFactory.getLogger(LanguagePacks.class);

    private LanguagePacks() {
    }

    /** All external packs on the classpath (META-INF/services). */
    public static List<LanguagePack> loadExternal() {
        return ServiceLoader.load(LanguagePack.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /**
     * Merges Spring-managed packs with external ServiceLoader packs.
     * Order: all springManaged first, then externals whose language() is not already covered.
     * Both split halves of a built-in pack may appear (chunker half + retriever half);
     * each engine side simply ignores the half it does not use.
     */
    public static List<LanguagePack> merge(List<LanguagePack> springManaged) {
        List<LanguagePack> result = new ArrayList<>(springManaged);
        Set<String> knownLanguages = new HashSet<>();
        for (LanguagePack pack : springManaged) {
            knownLanguages.add(pack.language());
        }
        for (LanguagePack pack : loadExternal()) {
            if (!knownLanguages.add(pack.language())) {
                log.warn("Ignoring external LanguagePack '{}' for language '{}': a Spring-managed pack already provides it",
                        pack.getClass().getName(), pack.language());
                continue;
            }
            result.add(pack);
        }
        return result;
    }
}
