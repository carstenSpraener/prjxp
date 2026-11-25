package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexical graph entry point behind the {@code graphSearch} tool.
 *
 * <p>Mirrors Graphify's {@code query_graph}: tokenise the question, score node labels/ids
 * and file paths (exact &gt; prefix &gt; substring, IDF-weighted, coverage-scaled), pick the
 * top seeds, then traverse the graph with BFS or DFS and return a bounded subgraph.
 * This is deliberately <b>lexical</b>, not semantic — the benchmark questions contain
 * identifiers, and a dense label match beats a chunk-embedding search for them.
 */
@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class GraphSearchService {

    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 6;
    private static final int DEFAULT_MAX_SEEDS = 3;
    private static final int MAX_MAX_SEEDS = 5;
    private static final double SEED_SCORE_FLOOR = 0.20;
    /** Rough rendered cost per node/edge line, used to trim to the token budget. */
    private static final int LINE_ESTIMATE_CHARS = 80;
    private static final int HEADER_ESTIMATE_CHARS = 200;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "how", "what", "where", "which", "does", "did", "are", "was",
            "this", "that", "from", "into", "about", "work", "works", "working", "use", "used", "using",
            "der", "die", "das", "und", "oder", "wie", "wo", "welche", "welcher", "welches",
            "ist", "sind", "wird", "werden", "ein", "eine", "einer", "eines", "für", "mit", "von",
            "zu", "zum", "zur", "im", "in", "am", "an", "auf", "den", "dem", "des", "es");

    private final ProjectRegistry projectRegistry;
    private final ProjectGraphStore graphStore;

    public GraphSearchView search(String project, String question, String mode, int depth,
                                  int tokenBudget, Set<EdgeType> contextFilter, int maxSeeds) {
        try {
            projectRegistry.ensureSearchable(project);
        } catch (UnknownProjectException e) {
            return GraphSearchView.unavailable(e.getMessage());
        }
        if (question == null || question.isBlank()) {
            return GraphSearchView.unavailable("Parameter 'question' ist erforderlich.");
        }
        CodeGraph graph = graphStore.graphFor(project).orElse(null);
        if (graph == null) {
            return GraphSearchView.unavailable("Graph nicht verfügbar, bitte reindexieren");
        }
        List<String> terms = tokenize(question);
        if (terms.isEmpty()) {
            return GraphSearchView.unavailable("Keine Suchbegriffe in der Frage gefunden.");
        }

        int safeDepth = Math.max(1, Math.min(depth <= 0 ? DEFAULT_DEPTH : depth, MAX_DEPTH));
        int safeSeeds = Math.max(1, Math.min(maxSeeds <= 0 ? DEFAULT_MAX_SEEDS : maxSeeds, MAX_MAX_SEEDS));
        List<String> seeds = pickSeeds(graph, terms, safeSeeds);
        if (seeds.isEmpty()) {
            return GraphSearchView.unavailable("Kein Graph-Knoten passt zu: " + question);
        }

        Set<String> visited = new LinkedHashSet<>(seeds);
        List<GraphSearchView.GraphSearchEdge> edges = traverse(graph, seeds, safeDepth, mode, contextFilter, visited);

        List<GraphSearchView.GraphSearchNode> nodes = new ArrayList<>();
        for (String id : visited) {
            GraphNode node = graph.nodeById().get(id);
            if (node != null) {
                nodes.add(new GraphSearchView.GraphSearchNode(
                        node.id(), node.file(), node.isMethod(), node.clusterId(), node.weight()));
            }
        }

        int totalNodes = nodes.size();
        int totalEdges = edges.size();
        boolean truncated = trimToBudget(nodes, edges, tokenBudget);

        return new GraphSearchView(project, seeds, nodes, edges, null, totalNodes, totalEdges, truncated);
    }

    /** Tokenises and normalises the question; drops short tokens and EN/DE stopwords. */
    static List<String> tokenize(String question) {
        String normalized = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        List<String> all = new ArrayList<>();
        for (String token : normalized.split("[^\\p{Alnum}_#.]+")) {
            if (token.length() > 2 && !STOPWORDS.contains(token)) {
                all.add(token);
            }
        }
        if (all.isEmpty()) {
            // Fallback: keep the raw tokens so a question made only of stopwords still searches.
            for (String token : normalized.split("[^\\p{Alnum}_#.]+")) {
                if (token.length() > 1) {
                    all.add(token);
                }
            }
        }
        return all;
    }

    private List<String> pickSeeds(CodeGraph graph, List<String> terms, int maxSeeds) {
        List<GraphNode> nodes = graph.nodes();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : terms) {
            int df = 0;
            for (GraphNode node : nodes) {
                if (matches(node, term)) {
                    df++;
                }
            }
            documentFrequency.put(term, df);
        }

        double n = Math.max(1, nodes.size());
        Map<String, Double> scores = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            double score = 0.0;
            int matched = 0;
            for (String term : terms) {
                boolean hit = false;
                double idf = Math.log(1.0 + n / (1.0 + documentFrequency.getOrDefault(term, 0)));
                String label = labelOf(node.id());
                if (label.equals(term) || node.id().equalsIgnoreCase(term)) {
                    score += 1000.0 * idf;
                    hit = true;
                } else if (label.startsWith(term) || node.id().toLowerCase(Locale.ROOT).startsWith(term)) {
                    score += 100.0 * idf;
                    hit = true;
                } else if (label.contains(term) || node.id().toLowerCase(Locale.ROOT).contains(term)) {
                    score += idf;
                    hit = true;
                }
                if (node.file() != null && node.file().toLowerCase(Locale.ROOT).contains(term)) {
                    score += 0.5 * idf;
                    hit = true;
                }
                if (hit) {
                    matched++;
                }
            }
            if (matched == 0) {
                continue;
            }
            double coverage = (double) matched / terms.size();
            scores.put(node.id(), score * coverage * coverage);
        }

        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }
        double topScore = ranked.get(0).getValue();
        List<String> seeds = new ArrayList<>();
        Set<String> seenLabels = new LinkedHashSet<>();
        for (Map.Entry<String, Double> entry : ranked) {
            if (entry.getValue() < topScore * SEED_SCORE_FLOOR) {
                break;
            }
            String label = labelOf(entry.getKey());
            if (!seenLabels.add(label)) {
                continue;
            }
            seeds.add(entry.getKey());
            if (seeds.size() >= maxSeeds) {
                break;
            }
        }
        return seeds;
    }

    private boolean matches(GraphNode node, String term) {
        String label = labelOf(node.id());
        return label.contains(term)
                || node.id().toLowerCase(Locale.ROOT).contains(term)
                || (node.file() != null && node.file().toLowerCase(Locale.ROOT).contains(term));
    }

    private record NodeDepth(String id, int depth) {
    }

    private List<GraphSearchView.GraphSearchEdge> traverse(
            CodeGraph graph, List<String> seeds, int depth, String mode,
            Set<EdgeType> contextFilter, Set<String> visited) {
        boolean dfs = "dfs".equalsIgnoreCase(mode);
        Set<String> seenEdges = new LinkedHashSet<>();
        List<GraphSearchView.GraphSearchEdge> edges = new ArrayList<>();
        Deque<NodeDepth> frontier = new ArrayDeque<>();
        if (dfs) {
            for (int i = seeds.size() - 1; i >= 0; i--) {
                frontier.push(new NodeDepth(seeds.get(i), 0));
            }
        } else {
            for (String seed : seeds) {
                frontier.addLast(new NodeDepth(seed, 0));
            }
        }

        while (!frontier.isEmpty()) {
            NodeDepth current = dfs ? frontier.pop() : frontier.pollFirst();
            if (current.depth() >= depth) {
                continue;
            }
            collectEdges(graph, current.id(), contextFilter, seenEdges, edges, visited,
                    frontier, dfs, current.depth() + 1);
        }
        return edges;
    }

    private void collectEdges(CodeGraph graph, String id, Set<EdgeType> contextFilter,
                              Set<String> seenEdges, List<GraphSearchView.GraphSearchEdge> edges,
                              Set<String> visited, Deque<NodeDepth> frontier, boolean dfs, int nextDepth) {
        for (GraphEdge edge : graph.edgesBySource().getOrDefault(id, List.of())) {
            if (!include(edge, contextFilter)) {
                continue;
            }
            addEdge(edge, seenEdges, edges);
            enqueue(edge.target(), visited, frontier, dfs, nextDepth);
        }
        for (GraphEdge edge : graph.edgesByTarget().getOrDefault(id, List.of())) {
            if (!include(edge, contextFilter)) {
                continue;
            }
            addEdge(edge, seenEdges, edges);
            enqueue(edge.source(), visited, frontier, dfs, nextDepth);
        }
    }

    private void addEdge(GraphEdge edge, Set<String> seenEdges,
                         List<GraphSearchView.GraphSearchEdge> edges) {
        String key = edge.source() + "\u0000" + edge.target() + "\u0000" + edge.type();
        if (seenEdges.add(key)) {
            edges.add(new GraphSearchView.GraphSearchEdge(
                    edge.source(), edge.target(), edge.type(), edge.external()));
        }
    }

    private void enqueue(String id, Set<String> visited, Deque<NodeDepth> frontier,
                         boolean dfs, int depth) {
        if (id != null && visited.add(id)) {
            NodeDepth node = new NodeDepth(id, depth);
            if (dfs) {
                frontier.push(node);
            } else {
                frontier.addLast(node);
            }
        }
    }

    private boolean include(GraphEdge edge, Set<EdgeType> contextFilter) {
        if (edge.external()) {
            return false;
        }
        return contextFilter == null || contextFilter.isEmpty() || contextFilter.contains(edge.type());
    }

    /** Trims nodes/edges from the end until the rough render cost fits the token budget. */
    private boolean trimToBudget(List<GraphSearchView.GraphSearchNode> nodes,
                                 List<GraphSearchView.GraphSearchEdge> edges, int tokenBudget) {
        int budgetChars = Math.max(1, tokenBudget) * ToolResultCaps.CHARS_PER_TOKEN;
        int estimate = HEADER_ESTIMATE_CHARS
                + nodes.size() * LINE_ESTIMATE_CHARS
                + edges.size() * LINE_ESTIMATE_CHARS;
        boolean truncated = false;
        while (estimate > budgetChars && !edges.isEmpty()) {
            edges.remove(edges.size() - 1);
            estimate -= LINE_ESTIMATE_CHARS;
            truncated = true;
        }
        while (estimate > budgetChars && nodes.size() > 1) {
            nodes.remove(nodes.size() - 1);
            estimate -= LINE_ESTIMATE_CHARS;
            truncated = true;
        }
        return truncated;
    }

    private static String labelOf(String id) {
        int hash = id.indexOf('#');
        String raw = hash >= 0 ? id.substring(hash + 1) : id.substring(id.lastIndexOf('.') + 1);
        int paren = raw.indexOf('(');
        return (paren >= 0 ? raw.substring(0, paren) : raw).toLowerCase(Locale.ROOT);
    }
}
