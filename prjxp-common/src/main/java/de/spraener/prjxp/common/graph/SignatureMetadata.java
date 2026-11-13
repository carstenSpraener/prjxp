package de.spraener.prjxp.common.graph;

/** Metadata keys of the dual-graph signature layer (TF-IDF / c-TF-IDF / neighbours). */
public final class SignatureMetadata {
    /** Comma-separated top terms of the chunk via TF-IDF (written by tibed). */
    public static final String KEYWORDS = "keywords";
    /** Comma-separated top terms of the cluster via c-TF-IDF (written by tibed). */
    public static final String CLUSTER_KEYWORDS = "cluster_keywords";
    /** Chunk ids of the direct AST neighbours, separated by {@link #LIST_SEPARATOR}. */
    public static final String AST_NEIGHBOURS = "ast_neighbours";
    /** Chunk ids of the semantic neighbours, separated by {@link #LIST_SEPARATOR}. */
    public static final String SEMANTIC_NEIGHBOURS = "semantic_neighbours";
    /**
     * Separator for chunk-id lists. A comma is unsafe: chunk ids of methods embed their
     * parameter list (e.g. {@code Foo.bar(int, String)}), so a comma-separated list would be
     * split inside a signature.
     */
    public static final String LIST_SEPARATOR = "\u001F";
    /** Syntactic path of a doc chunk (already written by the MarkdownChunker). */
    public static final String HIERARCHY = "hierarchy";
    private SignatureMetadata() {}
}
