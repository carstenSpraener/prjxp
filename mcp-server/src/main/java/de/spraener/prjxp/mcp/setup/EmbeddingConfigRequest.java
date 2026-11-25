package de.spraener.prjxp.mcp.setup;
public record EmbeddingConfigRequest(String type, String apiBaseURL, String apiKey, String modelName,
                                     @jakarta.validation.constraints.Positive Integer dimensions,
                                     @jakarta.validation.constraints.Positive Integer timeout,
                                     @jakarta.validation.constraints.Positive Integer maxSegmentsPerBatch,
                                     Boolean resetDimensions) {
    @Override public String toString() { return "EmbeddingConfigRequest[redacted]"; }
}
