package de.spraener.gldrtrvr.chunks;

import de.spraener.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Profile("!test") // Aktiv, wenn nicht im Test-Profil
@RequiredArgsConstructor
@Primary
public class ChromaDBPxChunkDao implements PxChunkDao {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    // Wir brauchen kein EmbeddingModel für findById/Metadata,
    // da wir nur Metadata-Filter nutzen, keine Vektor-Suche.

    @Override
    public List<PxChunk> findById(String id) {
        // In LangChain4j 1.12.1 nutzen wir Filter, um nach der ID zu suchen
        Filter filter = new IsEqualTo(PxChunk.PXCHUNK_ID, id);

        // Suche über Metadaten-Filter (wir nutzen eine leere Vektor-Suche
        // oder die spezifische Filter-API des Stores)
        return searchWithFilter(filter);
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        if (metaData == null || metaData.isEmpty()) {
            return List.of();
        }

        // Wir bauen eine Kette von AND-Filtern für alle MetaData-Einträge
        Filter combinedFilter = null;
        for (var entry : metaData.entrySet()) {
            // Beachte dein Präfix-System aus PxChunk.metadataAsMap
            String key = entry.getKey().startsWith(PxChunk.PXCHUNK_METADATA)
                    ? entry.getKey()
                    : PxChunk.PXCHUNK_METADATA + "." + entry.getKey();

            Filter currentFilter = new IsEqualTo(key, entry.getValue());
            combinedFilter = (combinedFilter == null)
                    ? currentFilter
                    : new And(combinedFilter, currentFilter);
        }

        return searchWithFilter(combinedFilter);
    }

    @Override
    public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        // 1. Frage in Vektor umwandeln
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Die relevantesten Code-Chunks aus Chroma suchen
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> relevantMatches = searchResult.matches();
        return relevantMatches.stream()
                .map(TextSegment2PxChunkConverter::convert)
                .toList();
    }

    private List<PxChunk> searchWithFilter(Filter filter) {
        Embedding dummyEmbedding = Embedding.from(new float[1024]);
        // Wir führen eine Suche aus, die nur auf Metadaten basiert (max 100 Treffer)
        // Hinweis: EmbeddingStore.search gibt oft Scored-Matches zurück
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .filter(filter)
                .maxResults(100)
                .build();

        return embeddingStore.search(request)
                .matches().stream()
                .map(match -> {
                    TextSegment segment = match.embedded();
                    // Nutzt deine neue Rückwärts-Konvertierung!
                    return PxChunk.fromContentAndMap(segment.text(), segment.metadata().toMap());
                })
                .collect(Collectors.toList());
    }
}
