package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1 {@link GraphWeighter}.
 *
 * <ul>
 *   <li><b>weight</b> = normalized degree centrality: {@code (in + out degree) / maxDegree},
 *       in {@code [0,1]} (see {@link DegreeCentrality}). Central (high-degree) nodes score
 *       high, peripheral nodes low — which is what the retriever/vector-search re-ranking
 *       consumes.</li>
 *   <li><b>clusterId</b> = connected component over the undirected edge set (BFS), labelled
 *       {@code c0}, {@code c1}, … in node order. A cheap stand-in for Leiden communities;
 *       the Leiden implementation replaces this in v2.</li>
 * </ul>
 *
 * <p>Language-agnostic: operates on the abstract {@link CodeGraph}, so any language pack
 * that builds a graph can reuse it.
 */
@Component
public class CentralityWeighter implements GraphWeighter {

    @Override
    public CodeGraph apply(CodeGraph graph) {
        if (graph.nodes().isEmpty()) {
            return graph;
        }

        Map<String, Double> weights = DegreeCentrality.weights(graph);

        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            adjacency.put(n.id(), new HashSet<>());
        }
        for (GraphEdge e : graph.edges()) {
            adjacency.computeIfAbsent(e.source(), k -> new HashSet<>()).add(e.target());
            adjacency.computeIfAbsent(e.target(), k -> new HashSet<>()).add(e.source());
        }

        Map<String, String> cluster = new HashMap<>();
        Set<String> visited = new HashSet<>();
        int component = 0;
        for (GraphNode n : graph.nodes()) {
            if (!visited.contains(n.id())) {
                bfs(n.id(), adjacency, visited, cluster, "c" + component++);
            }
        }

        List<GraphNode> rated = graph.nodes().stream()
                .map(n -> n.withRating(cluster.get(n.id()), weights.getOrDefault(n.id(), 0.0)))
                .toList();
        return new CodeGraph(rated, graph.edges());
    }

    private void bfs(String start, Map<String, Set<String>> adjacency, Set<String> visited,
                     Map<String, String> cluster, String clusterId) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            cluster.put(node, clusterId);
            for (String neighbor : adjacency.getOrDefault(node, Set.of())) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
    }
}
