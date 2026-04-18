package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.model.PxChunk;

import java.util.List;
import java.util.Map;

public interface PxChunkDao {
    List<PxChunk> findById(String id);

    List<PxChunk> findByMetaData(Map<String, String> metaData);

    List<PxChunk> findRelevant(String question, int maxResults, double minScore);
}
