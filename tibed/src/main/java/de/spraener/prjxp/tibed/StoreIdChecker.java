package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoreIdChecker {
    private final PrjXPConfig cfg;

    public boolean containsChunk(EmbeddingStore<TextSegment> store, String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return false;
        }
        Filter filter = new IsEqualTo(PxChunk.PXCHUNK_ID, chunkId);
        if (store instanceof LuceneEmbeddingStore luceneStore) {
            return luceneStore.hasMatch(filter);
        }
        Embedding dummyEmbedding = Embedding.from(new float[cfg.getEmbeddingStoreLucene().getVectorDimension()]);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .filter(filter)
                .maxResults(1)
                .build();
        return !store.search(request).matches().isEmpty();
    }

    public boolean needsImport(EmbeddingStore<TextSegment> store, String chunkId) {
        return !containsChunk(store, chunkId);
    }
}
