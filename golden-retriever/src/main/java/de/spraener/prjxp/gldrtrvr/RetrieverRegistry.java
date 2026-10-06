package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePacks;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merged view over all GoldenRetriever instances:
 * 1. retrievers provided by LanguagePacks (Spring-managed first, then external
 *    ServiceLoader packs via LanguagePacks.merge; dedup by language(), sorted),
 * 2. plain GoldenRetriever beans not provided by any pack (e.g. MarkdownRetriever).
 */
@Service
@RequiredArgsConstructor
public class RetrieverRegistry {

    private final List<LanguagePack> languagePacks;
    private final List<GoldenRetriever> retrieverBeans;

    public List<GoldenRetriever> all() {
        TreeMap<String, GoldenRetriever> byLanguage = new TreeMap<>();   // deterministische Reihenfolge
        for (LanguagePack pack : LanguagePacks.merge(languagePacks)) {
            if (pack.retriever().isPresent()) {
                byLanguage.putIfAbsent(pack.language(), pack.retriever().orElseThrow());
            }
        }
        List<GoldenRetriever> result = new ArrayList<>(byLanguage.values());

        Set<GoldenRetriever> provided = new HashSet<>(result);
        for (GoldenRetriever bean : retrieverBeans) {
            if (!provided.contains(bean)) {
                result.add(bean);
            }
        }
        return result;
    }
}
