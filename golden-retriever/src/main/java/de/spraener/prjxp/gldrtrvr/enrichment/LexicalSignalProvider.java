package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;

import java.util.List;
import java.util.Map;

/**
 * The exact-token channel for the enrichment: candidates from the analyzed index, deliberately
 * <em>not</em> thresholded. A similarity score answers "how close is this meaning?"; a token hit
 * answers "does this occur?" — only the first may be filtered by a cosine threshold.
 * <p>
 * Stores without an analyzed index simply have no second channel.
 */
public class LexicalSignalProvider {

    public List<ScoredChunk> candidates(PxChunkDao chunkDao, String prompt, Map<String, String> filters,
                                        int window) {
        if (chunkDao == null || prompt == null || prompt.isBlank() || window <= 0) {
            return List.of();
        }
        try {
            return chunkDao.searchCandidates(prompt, filters, window);
        } catch (UnsupportedOperationException e) {
            return List.of();
        }
    }
}
