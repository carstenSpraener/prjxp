package de.spraener.prjxp.common.model;

/** One undirected weighted edge supplied to weighted Leiden clustering. */
public record WeightedEdge(String source, String target, double weight) {}
