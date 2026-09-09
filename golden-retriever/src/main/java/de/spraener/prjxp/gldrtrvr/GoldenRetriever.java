package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;

import java.util.List;
import java.util.function.Function;

public interface GoldenRetriever {
    StringBuilder buildPromptForFindings(String projectName, List<PxChunk> chunks, Function<String, Boolean>... contextValidators);
    /**
     * This method takes a list of PxChunks and combines them to a list of SearchHits.
     * Each SearchHit combines all PxChunks from the same file. So if chunk A and chunk B reference the same file, they will be combined into one SearchHit.
     */
    List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators);
}
