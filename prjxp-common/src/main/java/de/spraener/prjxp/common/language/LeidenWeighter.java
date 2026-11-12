package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.WeightedEdge;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v2 default {@link GraphWeighter} (Phase 02: Graph-Bau).
 *
 * <ul>
 *   <li><b>weight</b> = normalized degree centrality ({@link DegreeCentrality}) —
 *       <i>never</i> community cohesion. Central (high-degree) nodes score high,
 *       peripheral nodes low.</li>
 *   <li><b>clusterId</b> = Leiden community over the undirected edge set, labelled
 *       {@code c0}, {@code c1}, … after {@code orderClustersByNNodes()}. Leiden only
 *       supplies the cluster ids; it does not influence the weight.</li>
 * </ul>
 *
 * <p>Deterministic: fixed seed {@code 42}, randomness {@code 0.0}, 10 iterations.
 * The undirected edge set aggregates {@code A→B} and {@code B→A} into one weighted pair
 * (duplicates sum); a self-loop counts once. Edges whose source or target is not a known
 * node id (external) are excluded from the network.
 *
 * <p><b>Resolution calibration (Phase 05 E2E):</b> CWTS optimizes the Constant Potts Model,
 * where the resolution is the minimum internal edge density a community must exceed. Code
 * graphs are sparse, so a fixed resolution of {@code 1.0} required a density above 100 %
 * and degenerated to one cluster per node (5 525 clusters for 5 541 nodes on the prjxp
 * graph). The resolution is therefore derived from the graph size:
 * {@code γ = 2 / sqrt(n)}, i.e. "target communities of about sqrt(n) nodes" (a tree-like
 * community of size {@code s} has ~{@code s} edges and {@code s(s-1)/2} pairs, hence a
 * density of ~{@code 2/s}). On the prjxp graph that yields ~395 multi-node communities
 * (top cluster 91 nodes, ~90 % module purity); isolated nodes stay singletons.
 */
@Component
@Primary
public class LeidenWeighter implements GraphWeighter {

    private static final int N_ITERATIONS = 10;
    private static final double RANDOMNESS = 0.0;
    private static final long SEED = 42L;

    @Override
    public CodeGraph apply(CodeGraph graph) {
        if (graph.nodes().isEmpty()) {
            return graph;
        }

        Map<String, Double> weights = DegreeCentrality.weights(graph);
        Map<String, String> clusters = leidenClusters(graph);

        List<GraphNode> rated = graph.nodes().stream()
                .map(n -> n.withRating(clusters.get(n.id()), weights.getOrDefault(n.id(), 0.0)))
                .toList();
        return new CodeGraph(rated, graph.edges());
    }

    /**
     * Clusters an explicit weighted undirected graph. The supplied gamma is used directly;
     * the code-graph resolution heuristic remains unchanged.
     */
    public Map<String, String> clusterWeighted(
            List<String> nodeIds, List<WeightedEdge> weightedEdges, double gamma) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (String id : nodeIds) {
            index.putIfAbsent(id, index.size());
        }

        int nodeCount = index.size();
        Map<String, String> clusterOf = new LinkedHashMap<>();
        if (nodeCount == 0) {
            return clusterOf;
        }
        if (nodeCount == 1) {
            clusterOf.put(index.keySet().iterator().next(), "s0");
            return clusterOf;
        }

        Map<Long, double[]> aggregated = new LinkedHashMap<>();
        for (WeightedEdge edge : weightedEdges) {
            Integer first = index.get(edge.source());
            Integer second = index.get(edge.target());
            if (first == null || second == null || first.equals(second)) {
                continue;
            }

            int low = Math.min(first, second);
            int high = Math.max(first, second);
            long key = ((long) low << 32) | (high & 0xffffffffL);
            double[] entry = aggregated.get(key);
            if (entry == null) {
                aggregated.put(key, new double[]{low, high, edge.weight()});
            } else {
                entry[2] += edge.weight();
            }
        }

        if (aggregated.isEmpty()) {
            int cluster = 0;
            for (String id : index.keySet()) {
                clusterOf.put(id, "s" + cluster++);
            }
            return clusterOf;
        }

        int edgeCount = aggregated.size();
        LargeIntArray[] edges = {new LargeIntArray(edgeCount), new LargeIntArray(edgeCount)};
        LargeDoubleArray edgeWeights = new LargeDoubleArray(edgeCount);
        int edgeIndex = 0;
        for (double[] entry : aggregated.values()) {
            edges[0].set(edgeIndex, (int) entry[0]);
            edges[1].set(edgeIndex, (int) entry[1]);
            edgeWeights.set(edgeIndex, entry[2]);
            edgeIndex++;
        }

        Network network = new Network(nodeCount, false, edges, edgeWeights, false, true);
        LeidenAlgorithm algorithm = new LeidenAlgorithm(
                gamma, N_ITERATIONS, RANDOMNESS, new Random(SEED));
        Clustering clustering = algorithm.findClustering(network);
        clustering.orderClustersByNNodes();
        for (Map.Entry<String, Integer> entry : index.entrySet()) {
            clusterOf.put(entry.getKey(), "s" + clustering.getCluster(entry.getValue()));
        }
        return clusterOf;
    }

    /** Maps every node id to its Leiden cluster id ({@code c0}, {@code c1}, …). */
    private Map<String, String> leidenClusters(CodeGraph graph) {
        // Node ids to dense integer indices in node order (first occurrence wins for duplicates).
        // The index must stay dense: Network requires every edge endpoint < nNodes.
        Map<String, Integer> index = new HashMap<>();
        List<GraphNode> nodes = graph.nodes();
        for (GraphNode node : nodes) {
            index.putIfAbsent(node.id(), index.size());
        }
        int n = index.size();

        Map<String, String> clusterOf = new HashMap<>();
        if (n == 1) {
            clusterOf.put(nodes.get(0).id(), "c0");
            return clusterOf;
        }

        // Aggregate the undirected edge set: A→B and B→A collapse into one weighted
        // pair, duplicates sum, a self-loop counts once.
        Map<Long, double[]> aggregated = new HashMap<>(); // key -> {lo, hi, weight}
        for (GraphEdge e : graph.edges()) {
            Integer a = index.get(e.source());
            Integer b = index.get(e.target());
            if (a == null || b == null) {
                continue; // external endpoint — not part of the network
            }
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            long key = ((long) lo << 32) | hi;
            double[] entry = aggregated.get(key);
            if (entry == null) {
                aggregated.put(key, new double[]{lo, hi, 1.0});
            } else {
                entry[2] += 1.0;
            }
        }

        if (aggregated.isEmpty()) {
            // No internal edges: every node is its own cluster.
            int c = 0;
            for (GraphNode node : nodes) {
                clusterOf.putIfAbsent(node.id(), "c" + c++);
            }
            return clusterOf;
        }

        int m = aggregated.size();
        LargeIntArray[] edges = {new LargeIntArray(m), new LargeIntArray(m)};
        LargeDoubleArray edgeWeights = new LargeDoubleArray(m);
        int i = 0;
        for (double[] entry : aggregated.values()) {
            edges[0].set(i, (int) entry[0]);
            edges[1].set(i, (int) entry[1]);
            edgeWeights.set(i, entry[2]);
            i++;
        }

        Network network = new Network(n, false, edges, edgeWeights, false, true);
        LeidenAlgorithm algorithm = new LeidenAlgorithm(resolutionFor(n), N_ITERATIONS, RANDOMNESS, new Random(SEED));
        Clustering clustering = algorithm.findClustering(network);
        clustering.orderClustersByNNodes();

        for (Map.Entry<String, Integer> e : index.entrySet()) {
            clusterOf.put(e.getKey(), "c" + clustering.getCluster(e.getValue()));
        }
        return clusterOf;
    }

    /**
     * CPM resolution for a graph with {@code n} nodes: {@code 2 / sqrt(n)}.
     *
     * <p>A tree-like community of size {@code s} has ~{@code s} internal edges and
     * {@code s(s-1)/2} node pairs, i.e. an internal density of about {@code 2/s}. Targeting
     * communities of ~{@code sqrt(n)} nodes therefore means requiring a density of
     * {@code 2/sqrt(n)}. This keeps the ids meaningful across graph sizes — a fixed
     * resolution cannot serve both a 6-node unit-test graph and a 5 500-node project graph
     * (see the class Javadoc).
     */
    private static double resolutionFor(int n) {
        return 2.0 / Math.sqrt(n);
    }
}
