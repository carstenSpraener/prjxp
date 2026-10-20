package de.spraener.prjxp.common.language;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single source of truth for all {@link LanguagePack}s at runtime:
 * built-in Spring-managed packs plus externally loaded plugin packs,
 * resolved once at startup.
 *
 * <p>Conflict rule: a plugin pack wins over a built-in pack with the same
 * {@code language()} value; a warning names both packs. Built-in split
 * halves (chunker half + retriever half) of one language coexist.
 *
 * <p>Consumed by {@code LanguagePackBroker} (chunk-norris),
 * {@code RetrieverRegistry} (golden-retriever) and {@code GrepSearchService}
 * (mcp-server) — all of them read the same resolved view, so a plugin pack
 * is visible to chunking, retrieval and the grep language filter alike.
 */
@Component
public class LanguagePackRegistry {

    private static final Logger log = LoggerFactory.getLogger(LanguagePackRegistry.class);

    private final List<LanguagePack> packs;
    private final Map<String, String> mimeByLanguage;

    @Autowired
    public LanguagePackRegistry(ObjectProvider<LanguagePack> builtInPacks,
                                ObjectProvider<LanguagePackPluginLoader> pluginLoader) {
        this(builtInPacks.orderedStream().toList(),
                pluginLoader.stream().map(LanguagePackPluginLoader::loadedPacks).flatMap(List::stream).toList());
    }

    /**
     * Resolves the given packs into the registry view.
     *
     * <p>Result order: built-in packs in the given order (minus those
     * overridden by a plugin), then the winning plugin packs in the given
     * order. A plugin pack wins over built-in packs with the same
     * {@code language()} value (a warning names both); among multiple plugin
     * packs for one language the first wins.
     */
    public LanguagePackRegistry(List<LanguagePack> builtInPacks, List<LanguagePack> pluginPacks) {
        Map<String, List<LanguagePack>> pluginByLanguage = groupByLanguage(pluginPacks);

        List<LanguagePack> resolved = new ArrayList<>();
        for (LanguagePack pack : builtInPacks) {
            List<LanguagePack> plugins = pluginByLanguage.get(normalize(pack.language()));
            if (plugins == null) {
                resolved.add(pack);
            } else {
                log.warn("Plugin LanguagePack '{}' overrides built-in LanguagePack '{}' for language '{}'",
                        plugins.get(0).getClass().getName(), pack.getClass().getName(), pack.language());
            }
        }
        for (List<LanguagePack> plugins : pluginByLanguage.values()) {
            LanguagePack winner = plugins.get(0);
            resolved.add(winner);
            for (int i = 1; i < plugins.size(); i++) {
                log.warn("Ignoring plugin LanguagePack '{}' for language '{}': plugin '{}' already provides it",
                        plugins.get(i).getClass().getName(), winner.language(), winner.getClass().getName());
            }
        }
        this.packs = List.copyOf(resolved);

        Map<String, String> mimes = new HashMap<>();
        for (LanguagePack pack : resolved) {
            mimes.put(normalize(pack.language()), pack.mimeType());
        }
        this.mimeByLanguage = Map.copyOf(mimes);
    }

    /** All resolved packs: built-ins (minus overridden ones) + winning plugins. */
    public List<LanguagePack> all() {
        return packs;
    }

    /** Normalized (lower-case) language -> MIME type of the winning pack. */
    public Map<String, String> mimeByLanguage() {
        return mimeByLanguage;
    }

    private static Map<String, List<LanguagePack>> groupByLanguage(List<LanguagePack> packs) {
        Map<String, List<LanguagePack>> byLanguage = new LinkedHashMap<>();
        for (LanguagePack pack : packs) {
            byLanguage.computeIfAbsent(normalize(pack.language()), l -> new ArrayList<>()).add(pack);
        }
        return byLanguage;
    }

    private static String normalize(String language) {
        return language.toLowerCase(Locale.ROOT);
    }
}
