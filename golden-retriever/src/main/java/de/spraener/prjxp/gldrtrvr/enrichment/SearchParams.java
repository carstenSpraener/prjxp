package de.spraener.prjxp.gldrtrvr.enrichment;

import lombok.Data;

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
}
