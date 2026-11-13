package de.spraener.prjxp.common.retrieval;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class SearchParams {
    private int maxResult = 8;
    private int maxContentLength = 50000;
    private double minScore = 0.85;
    private double initialMinScore = 0.85;
    private double effectiveMinScore = 0.85;
    private int fallbackRounds = 0;
    private boolean skeletonsOnly = false;
    private boolean abort = false;
    private ContentLevel contentLevel = ContentLevel.FULL;
    private Map<String, String> vectorFilters = new HashMap<>();
    /**
     * Efficiency measure 4 — maximum number of chunks kept per distinct file. A long document
     * split into many chunks would otherwise flood the context with near-duplicate pointer
     * blocks; only the highest-scoring {@code maxChunksPerFile} chunks of each file are kept.
     * A value {@code <= 0} disables the per-file cap.
     */
    private int maxChunksPerFile = 3;

    public SearchParams(int maxResults, double minScore) {
        this(maxResults, minScore, false);
    }

    public SearchParams(int maxResults, double minScore, boolean skeletonsOnly) {
        this.maxResult = maxResults;
        this.minScore = minScore;
        this.initialMinScore = minScore;
        this.effectiveMinScore = minScore;
        this.skeletonsOnly = skeletonsOnly;
    }

    /** Effective content level: an explicit {@code contentLevel} wins; the legacy {@code skeletonsOnly} maps to SKELETON when contentLevel is still FULL. */
    public ContentLevel effectiveContentLevel() {
        if (contentLevel != null && contentLevel != ContentLevel.FULL) return contentLevel;
        return skeletonsOnly ? ContentLevel.SKELETON : ContentLevel.FULL;
    }
}
