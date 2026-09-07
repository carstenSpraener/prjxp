package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In-Memory-Store, der das Verhalten des Lucene-Stores nachahmt:
 * Jeder Add erzeugt einen neuen Eintrag (keine automatische Dedup),
 * IDs werden aus dem Segment-Metadaten extrahiert.
 */
class FakeEmbeddingStore implements EmbeddingStore<TextSegment> {
    private final Map<String, Embedding> embeddings = new LinkedHashMap<>();
    private final Map<String, TextSegment> segments = new LinkedHashMap<>();
    private int counter = 0;
    final List<Integer> addAllBatchSizes = new ArrayList<>();

    @Override
    public String add(Embedding embedding) {
        return add(embedding, null);
    }

    @Override
    public void add(String id, Embedding embedding) {
        embeddings.put(id, embedding);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = extractId(textSegment) + "#" + (counter++);
        embeddings.put(id, embedding);
        if (textSegment != null) {
            segments.put(id, textSegment);
        }
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return embeddings.stream().map(this::add).toList();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("size mismatch");
        }
        addAllBatchSizes.add(embeddings.size());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(add(embeddings.get(i), textSegments.get(i)));
        }
        return ids;
    }

    private String extractId(TextSegment textSegment) {
        if (textSegment != null) {
            Map<String, Object> meta = textSegment.metadata().toMap();
            if (meta.containsKey("id")) {
                return meta.get("id").toString();
            }
            if (meta.containsKey(PxChunk.PXCHUNK_ID)) {
                return meta.get(PxChunk.PXCHUNK_ID).toString();
            }
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (Map.Entry<String, Embedding> entry : embeddings.entrySet()) {
            TextSegment segment = segments.get(entry.getKey());
            if (segment == null) {
                continue;
            }
            if (request.filter() != null && !matchesFilter(segment, request.filter())) {
                continue;
            }
            matches.add(new EmbeddingMatch<>(1.0, entry.getKey(), entry.getValue(), segment));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    private boolean matchesFilter(TextSegment segment, Filter filter) {
        Map<String, Object> meta = segment.metadata().toMap();
        if (filter instanceof IsEqualTo eq) {
            return Objects.equals(meta.get(eq.key()), eq.comparisonValue());
        }
        if (filter instanceof IsNotEqualTo ne) {
            return !Objects.equals(meta.get(ne.key()), ne.comparisonValue());
        }
        throw new UnsupportedOperationException("FakeEmbeddingStore supports only equality filters");
    }

    @Override
    public void removeAll(Filter filter) {
        embeddings.keySet().removeIf(id -> {
            TextSegment segment = segments.get(id);
            return segment != null && matchesFilter(segment, filter);
        });
        segments.keySet().removeIf(id -> !embeddings.containsKey(id));
    }

    int size() {
        return embeddings.size();
    }

    List<TextSegment> allSegments() {
        return new ArrayList<>(segments.values());
    }

    List<Embedding> allEmbeddings() {
        return new ArrayList<>(embeddings.values());
    }
}
