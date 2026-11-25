package de.spraener.prjxp.common.model;

/**
 * Typed edge categories of the code knowledge graph.
 *
 * <p>These are structural relations extracted from the AST (not the free-text
 * dependency chunks). {@code CALL} is method-to-method; the others are type-level.
 *
 * <p>{@code CONTAINS}, {@code GENERIC_ARG} and {@code ANNOTATION} are
 * <b>structural</b> edges: they make the graph navigable for the tools but are excluded
 * from the degree/cluster weighting (see {@link #isStructural()}), so adding them does
 * not move the {@code leiden_*} metadata or the retrieval ranking.
 */
public enum EdgeType {
    /** Source type imports the target type. */
    IMPORT,
    /** Source type extends the target type. */
    EXTENDS,
    /** Source type implements the target type. */
    IMPLEMENTS,
    /** Source type declares a field of the target type. */
    FIELD,
    /** Source method has a parameter of the target type. */
    PARAM,
    /** Source method returns the target type. */
    RETURN,
    /**
     * Source method invokes the target method. A {@code CALL} may also point at a type
     * node when the call is a constructor without an explicit constructor node.
     */
    CALL,
    /** Source type declares the target method or constructor (class -> method). */
    CONTAINS,
    /** Source declaration references the target type as a generic argument ({@code List<Foo>}). */
    GENERIC_ARG,
    /** Source declaration is annotated with the target annotation type. */
    ANNOTATION;

    /** Structural navigation edges that must not influence degree centrality or Leiden. */
    public boolean isStructural() {
        return this == CONTAINS || this == GENERIC_ARG || this == ANNOTATION;
    }
}
