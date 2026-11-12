package de.spraener.prjxp.common.model;

/** Calibration metadata written by tibed and read by the semantic graph views. */
public record SemanticGraphSidecar(String model, double gamma, double threshold,
                                   int k, boolean mutual, int chunkCount, String timestamp) {}
