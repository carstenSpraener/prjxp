package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface PxChunkDao {
    PrjXPEmbeddingStoreReference  getStoreReference();
    List<PxChunk> findById(String id);
    List<PxChunk> findByMetaData(Map<String, String> metaData);
    List<PxChunk> findRelevant(String question, int maxResults, double minScore);
    Stream<PxChunk> findAll();

    default List<ScoredChunk> searchFullText(String query, Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Full-text search is not supported by this store");
    }

    /**
     * Deterministic lookup on exact index fields (e.g. symbol metadata).
     * Filters use logical metadata keys, see {@link PxChunk#metadataFieldKey(String)}.
     */
    default List<ScoredChunk> searchByIndex(Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Index search is not supported by this store");
    }
}
