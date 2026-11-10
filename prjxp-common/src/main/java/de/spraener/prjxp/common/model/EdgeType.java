package de.spraener.prjxp.common.model;

/**
 * Typed edge categories of the code knowledge graph.
 *
 * <p>These are structural relations extracted from the AST (not the free-text
 * dependency chunks). {@code CALL} is method-to-method; the others are type-level.
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
    /** Source method invokes the target method. */
    CALL
}
