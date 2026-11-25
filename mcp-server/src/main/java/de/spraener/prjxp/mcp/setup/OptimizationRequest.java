package de.spraener.prjxp.mcp.setup;
public record OptimizationRequest(Integer vectorDimension, Integer maxSegmentsPerBatch,
                                   Integer docpipeMaxThreads, Integer readerMaxOutputChars,
                                   Double graphRerankFloor, Double graphRerankBGraph,
                                   Double graphRerankCap, Double graphRerankWBase, Integer embeddingTimeout) {}
