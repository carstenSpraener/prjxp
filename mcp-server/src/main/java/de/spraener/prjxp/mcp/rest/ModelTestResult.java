package de.spraener.prjxp.mcp.rest;

import lombok.Data;

@Data
public class ModelTestResult {
    private boolean success;
    private String message;
    private long latencyMs;
    private String response;      // nur Chat-Test
    private Integer dimensions;   // nur Embedding-Test

    public static ModelTestResult success(String message, long latencyMs, String response, Integer dimensions) {
        ModelTestResult r = new ModelTestResult();
        r.success = true; r.message = message; r.latencyMs = latencyMs;
        r.response = response; r.dimensions = dimensions;
        return r;
    }

    public static ModelTestResult failure(String message, long latencyMs) {
        ModelTestResult r = new ModelTestResult();
        r.success = false; r.message = message; r.latencyMs = latencyMs;
        return r;
    }
}
