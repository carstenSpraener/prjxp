package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalized degree centrality for a {@link CodeGraph}.
 *
 * <p>Shared by all {@link GraphWeighter} implementations: the node <b>weight</b> is
 * always degree centrality, independent of how the cluster ids are computed
 * (connected components, Leiden, …).
 */
public final class DegreeCentrality {

    private DegreeCentrality() {
    }

    /**
     * Normalized degree centrality {@code (in + out degree) / maxDegree} in {@code [0,1]}
     * for every node of the graph; an empty graph yields an empty map.
     *
     * <p>The degree map is seeded with all node ids (degree 0) and then incremented for
     * every edge endpoint — external targets therefore also become keys and count toward
     * the maximum degree. Only graph nodes receive a weight; a graph without edges
     * yields weight {@code 0.0} for every node.
     */
    public static Map<String, Double> weights(CodeGraph graph) {
        Map<String, Integer> degree = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            degree.put(n.id(), 0);
        }
        for (GraphEdge e : graph.edges()) {
            degree.merge(e.source(), 1, Integer::sum);
            degree.merge(e.target(), 1, Integer::sum);
        }

        int maxDegree = degree.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<String, Double> weights = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            weights.put(n.id(), maxDegree == 0 ? 0.0 : degree.getOrDefault(n.id(), 0) / (double) maxDegree);
        }
        return weights;
    }
}
