package de.spraener.prjxp.tibed.semantic;

/** One undirected semantic edge with canonical endpoint order and Lucene cosine score. */
public record SemanticEdge(String source, String target, double strength) {}
