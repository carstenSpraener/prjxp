package de.spraener.prjxp.gldrtrvr.enrichment;

import lombok.Data;

@Data
public class SearchParams {
    private int maxResult = 8;
    private double minScore = 0.85;
    private boolean abort = false;

    public SearchParams(int maxResults, double minScore) {
        this.maxResult = maxResults;
        this.minScore = minScore;
    }
}
