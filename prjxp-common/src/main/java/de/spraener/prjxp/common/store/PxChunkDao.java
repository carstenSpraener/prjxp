package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;import de.spraener.prjxp.common.model.PxChunk;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface PxChunkDao {
    PrjXPEmbeddingStoreReference  getStoreReference();
    List<PxChunk> findById(String id);
    List<PxChunk> findByMetaData(Map<String, String> metaData);
    List<PxChunk> findRelevant(String question, int maxResults, double minScore);
    Stream<PxChunk> findAll();
}
