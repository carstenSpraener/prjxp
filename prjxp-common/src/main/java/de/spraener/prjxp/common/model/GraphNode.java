package de.spraener.prjxp.common.model;

/**
 * A node in the code knowledge graph.
 *
 * <p>{@code id} is the fully-qualified name: a type's FQN (e.g.
 * {@code de.spraener.Foo}) for {@code isMethod == false}, or a method signature
 * ({@code de.spraener.Foo#bar(int, String)}) for {@code isMethod == true}.
 *
 * <p>{@code clusterId} and {@code weight} are populated by a
 * {@link de.spraener.prjxp.common.language.GraphWeighter} after the graph is built;
 * they are {@code null}/{@code 0.0} on a freshly built (unrated) graph.
 */
public record GraphNode(
        String id,
        String file,
        String language,
        boolean isMethod,
        String clusterId,
        double weight) {

    /** A type node (unrated). */
    public static GraphNode type(String fqn, String file, String language) {
        return new GraphNode(fqn, file, language, false, null, 0.0);
    }

    /** A method node (unrated). */
    public static GraphNode method(String fqnSignature, String file, String language) {
        return new GraphNode(fqnSignature, file, language, true, null, 0.0);
    }

    /** Returns a copy of this node carrying the given rating. */
    public GraphNode withRating(String clusterId, double weight) {
        return new GraphNode(id, file, language, isMethod, clusterId, weight);
    }
}
