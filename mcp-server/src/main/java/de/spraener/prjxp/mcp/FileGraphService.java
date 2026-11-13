package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.reader.ReaderSupport;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the typed code-graph neighbourhood for the {@code graphFile} / {@code graphNode}
 * MCP tools.
 *
 * <p>Resolution principle: the INDEX is the source of truth for files (lenient
 * exact -> suffix -> basename lookup, mirroring {@link ReaderService}), the GRAPH is the
 * source of truth for nodes. A file's type node is taken from the {@code graph_node}
 * chunk metadata; a symbol is resolved against the graph's node ids (exact -> suffix).
 */
@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log
public class FileGraphService {

    private static final int FILE_LOOKUP_LIMIT = 10_000;
    private static final int DEFAULT_MAX_EDGES = 30;
    /** Raised 100 -> 500 (efficiency measure 2): a widely-used class (hundreds of refs) fits in
     *  one page; {@code offset} pages anything beyond that. */
    private static final int MAX_MAX_EDGES = 500;
    private static final int DEFAULT_TOP_N = 10;
    private static final int MAX_TOP_N = 50;
    private static final int DEFAULT_OVERVIEW_TOP_N = 10;
    private static final int MAX_OVERVIEW_TOP_N = 50;
    private static final int TOP_MEMBERS_PER_CLUSTER = 3;
    /** Efficiency measure 5: most-referenced classes listed per module group in the AGGREGATED view. */
    private static final int TOP_CLASSES_PER_MODULE = 5;
    /** Module label for edges whose neighbour is external (no file in the graph). */
    private static final String EXTERNAL_MODULE = "(external)";
    /** Module label for neighbours whose file path has no directory component. */
    private static final String ROOT_MODULE = "(root)";

    private final ProjectRegistry projectRegistry;
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ProjectGraphStore projectGraphStore;

    /** File lookup result: an early-exit view (ambiguous / not found) or the matched file with its chunks. */
    private record Located(Optional<GraphView> earlyExit, String matchedFile, List<PxChunk> chunks) {
        static Located early(GraphView view) {
            return new Located(Optional.of(view), null, null);
        }

        static Located matched(String file, List<PxChunk> chunks) {
            return new Located(Optional.empty(), file, chunks);
        }
    }

    /**
     * Returns the typed neighbours of the type node of one file: the file is resolved
     * leniently in the index, its {@code graph_node} metadata yields the type node id,
     * and the graph supplies the edges.
     */
    public GraphView graphFile(String file, String project, Integer maxEdges, Boolean includeExternal) {
        return graphFile(file, project, maxEdges, includeExternal, 0, ContentLevel.FULL);
    }

    public GraphView graphFile(String file, String project, Integer maxEdges, Boolean includeExternal, Integer offset) {
        return graphFile(file, project, maxEdges, includeExternal, offset, ContentLevel.FULL);
    }

    public GraphView graphFile(String file, String project, Integer maxEdges, Boolean includeExternal, Integer offset, ContentLevel contentLevel) {
        if (StringUtils.isBlank(file)) {
            return GraphView.unavailable("Parameter 'file' ist erforderlich.");
        }
        String requestedProject = project;
        GraphView error = ensureSearchableOrError(requestedProject);
        if (error != null) {
            return error;
        }
        CodeGraph graph = projectGraphStore.graphFor(requestedProject).orElse(null);
        if (graph == null) {
            return GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren");
        }
        PxChunkDao dao = chunkDaoProvider.get(projectRegistry.resolve(requestedProject)).orElse(null);
        if (dao == null) {
            return GraphView.unavailable("Projekt nicht suchbar, bitte reindexieren");
        }

        String requested = ReaderSupport.normalizePath(file);
        Located located;
        try {
            located = locateFile(dao, requested, file, requestedProject);
        } catch (UnsupportedOperationException e) {
            return GraphView.unavailable("Index-Suche wird von diesem Store nicht unterstützt");
        }
        if (located.earlyExit().isPresent()) {
            return located.earlyExit().get();
        }

        // Type node id from the chunks' graph_node metadata: prefer an id without '#',
        // otherwise cut the first method id at its '#'.
        Set<String> ids = new LinkedHashSet<>();
        for (PxChunk chunk : located.chunks()) {
            if (chunk.getMetadata() == null) {
                continue;
            }
            String id = chunk.getMetadata().get(GraphMetadata.GRAPH_NODE);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return GraphView.unavailable("Chunk ohne graph_node — bitte reindexieren");
        }
        String typeId = ids.stream().filter(id -> !id.contains("#")).findFirst().orElse(null);
        if (typeId == null) {
            String firstId = ids.iterator().next();
            int hash = firstId.indexOf('#');
            typeId = hash >= 0 ? firstId.substring(0, hash) : firstId;
        }
        GraphNode node = graph.nodeById().get(typeId);
        if (node == null) {
            return GraphView.unavailable("Node nicht im Graph — bitte reindexieren");
        }
        return toView(requestedProject, located.matchedFile(), graph, node, maxEdges, includeExternal, offset, contentLevel);
    }

    /**
     * Returns the typed neighbours of one node: the symbol is resolved against the
     * graph's node ids (exact, then suffix), ambiguous symbols yield a candidate list.
     */
    public GraphView graphNode(String symbol, String project, Integer maxEdges, Boolean includeExternal) {
        return graphNode(symbol, project, maxEdges, includeExternal, 0, ContentLevel.FULL);
    }

    public GraphView graphNode(String symbol, String project, Integer maxEdges, Boolean includeExternal, Integer offset) {
        return graphNode(symbol, project, maxEdges, includeExternal, offset, ContentLevel.FULL);
    }

    public GraphView graphNode(String symbol, String project, Integer maxEdges, Boolean includeExternal, Integer offset, ContentLevel contentLevel) {
        if (StringUtils.isBlank(symbol)) {
            return GraphView.unavailable("Parameter 'symbol' ist erforderlich.");
        }
        String requestedProject = project;
        GraphView error = ensureSearchableOrError(requestedProject);
        if (error != null) {
            return error;
        }
        CodeGraph graph = projectGraphStore.graphFor(requestedProject).orElse(null);
        if (graph == null) {
            return GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren");
        }

        Map<String, GraphNode> byId = graph.nodeById();
        String id;
        if (byId.containsKey(symbol)) {
            id = symbol;
        } else {
            List<String> matches = byId.keySet().stream()
                    .filter(nid -> nid.endsWith(symbol) || nid.endsWith("." + symbol) || nid.endsWith("#" + symbol))
                    .sorted()
                    .toList();
            if (matches.isEmpty()) {
                return GraphView.unavailable("Node nicht gefunden: " + symbol);
            }
            if (matches.size() > 1) {
                return GraphView.ambiguous(requestedProject, null, matches);
            }
            id = matches.get(0);
        }
        GraphNode node = byId.get(id);
        return toView(requestedProject, node.file(), graph, node, maxEdges, includeExternal, offset, contentLevel);
    }

    /**
     * Lists the {@code topN} most important members of one Leiden cluster, ranked by
     * {@code weight} (in + out degree), each with its (in, out) edge breakdown.
     */
    public ClusterView graphCluster(String clusterId, String project, Integer topN) {
        if (StringUtils.isBlank(clusterId)) {
            return ClusterView.unavailable("Parameter 'clusterId' ist erforderlich.");
        }
        try {
            projectRegistry.ensureSearchable(project);
        } catch (UnknownProjectException e) {
            return ClusterView.unavailable(e.getMessage());
        }
        CodeGraph graph = projectGraphStore.graphFor(project).orElse(null);
        if (graph == null) {
            return ClusterView.unavailable("Graph nicht verfügbar, bitte reindexieren");
        }
        List<GraphNode> members = graph.nodeById().values().stream()
                .filter(n -> clusterId.trim().equals(n.clusterId()))
                .sorted(Comparator.comparingDouble(GraphNode::weight).reversed()
                        .thenComparing(GraphNode::id))
                .toList();
        if (members.isEmpty()) {
            return ClusterView.unavailable("Cluster nicht gefunden: " + clusterId);
        }
        int cap = (topN == null || topN < 1) ? DEFAULT_TOP_N : Math.min(topN, MAX_TOP_N);
        Map<String, List<GraphEdge>> in = graph.edgesByTarget();
        Map<String, List<GraphEdge>> out = graph.edgesBySource();
        List<ClusterMember> top = members.stream().limit(cap)
                .map(n -> new ClusterMember(n.id(), n.file(), n.weight(),
                        in.getOrDefault(n.id(), List.of()).size(),
                        out.getOrDefault(n.id(), List.of()).size()))
                .toList();
        return new ClusterView(project, clusterId.trim(), members.size(), top, null);
    }

    /**
     * Returns a map of the project's Leiden clusters: the {@code topN} largest by member
     * count, each with its size and its most connected members (by {@code weight}).
     * The from-scratch architecture entry point — no starting class needed.
     */
    public ClusterOverviewView graphClusterOverview(String project, Integer topN) {
        try {
            projectRegistry.ensureSearchable(project);
        } catch (UnknownProjectException e) {
            return ClusterOverviewView.unavailable(e.getMessage());
        }
        CodeGraph graph = projectGraphStore.graphFor(project).orElse(null);
        if (graph == null) {
            return ClusterOverviewView.unavailable("Graph nicht verfügbar, bitte reindexieren");
        }
        Map<String, List<GraphNode>> byCluster = graph.nodeById().values().stream()
                .filter(n -> n.clusterId() != null)
                .collect(Collectors.groupingBy(GraphNode::clusterId));
        int cap = (topN == null || topN < 1) ? DEFAULT_OVERVIEW_TOP_N : Math.min(topN, MAX_OVERVIEW_TOP_N);
        List<ClusterSummary> clusters = byCluster.entrySet().stream()
                .sorted((a, b) -> {
                    int bySize = Integer.compare(b.getValue().size(), a.getValue().size());
                    return bySize != 0 ? bySize : a.getKey().compareTo(b.getKey());
                })
                .limit(cap)
                .map(e -> new ClusterSummary(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream()
                                .sorted(Comparator.comparingDouble(GraphNode::weight).reversed()
                                        .thenComparing(GraphNode::id))
                                .limit(TOP_MEMBERS_PER_CLUSTER)
                                .map(GraphNode::id)
                                .toList()))
                .toList();
        return new ClusterOverviewView(project, byCluster.size(), clusters, null);
    }

    /** Returns an error view when the project is not searchable, else null. */
    private GraphView ensureSearchableOrError(String requestedProject) {
        try {
            projectRegistry.ensureSearchable(requestedProject);
            return null;
        } catch (UnknownProjectException e) {
            return GraphView.unavailable(e.getMessage());
        }
    }

    /**
     * Lenient file resolution against the index (mirrors {@link ReaderService#locateFile}):
     * exact terms first, then a match-all scan with suffix/basename matching in memory.
     */
    private Located locateFile(PxChunkDao dao, String requested, String rawRequested, String project) {
        // 1. exact terms: try requested and "/" + requested (index stores rootDir-relative paths WITH leading slash)
        for (String term : List.of(requested, "/" + requested).stream().distinct().toList()) {
            List<ScoredChunk> hits = dao.searchByIndex(Map.of(PxChunk.PXCHUNK_FILE, term), FILE_LOOKUP_LIMIT);
            if (!hits.isEmpty()) {
                Set<String> files = hits.stream().map(h -> ReaderSupport.normalizePath(h.chunk().getFile()))
                        .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
                if (files.size() == 1) {
                    return Located.matched(files.iterator().next(), hits.stream().map(ScoredChunk::chunk).toList());
                }
                if (files.size() > 1) {
                    return Located.early(GraphView.ambiguous(project, rawRequested, List.copyOf(files)));
                }
            }
        }
        // 2. MatchAll fallback: distinct file values, then suffix / basename match in memory
        Map<String, List<PxChunk>> byFile = new LinkedHashMap<>();
        for (ScoredChunk h : dao.searchByIndex(Map.of(), FILE_LOOKUP_LIMIT)) {
            String n = ReaderSupport.normalizePath(h.chunk().getFile());
            if (n == null) {
                continue;
            }
            byFile.computeIfAbsent(n, k -> new ArrayList<>()).add(h.chunk());
        }
        String base = requested.substring(requested.lastIndexOf('/') + 1);
        List<String> matches = byFile.keySet().stream()
                .filter(f -> f.equals(requested) || f.endsWith("/" + requested)
                        || f.substring(f.lastIndexOf('/') + 1).equals(base))
                .toList();
        if (matches.size() > 1) {
            return Located.early(GraphView.ambiguous(project, rawRequested, matches));
        }
        if (matches.isEmpty()) {
            return Located.early(GraphView.unavailable("Datei nicht gefunden: " + rawRequested));
        }
        return Located.matched(matches.get(0), byFile.get(matches.get(0)));
    }

    /**
     * Builds the view: per-direction edge lists (external filtered, then paged by
     * {@code offset} and capped by {@code maxEdges}) with neighbour files. The full
     * per-direction counts (after the external filter, before paging) are exposed as
     * {@code totalOutgoing} / {@code totalIncoming} so the caller can page the rest.
     * <p>
     * With a non-{@link ContentLevel#FULL} content level (efficiency measure 5) the raw
     * edge lists are replaced by the module-level aggregation: the aggregation covers
     * ALL edges, so paging is meaningless and the edge lists come back empty.
     */
    private GraphView toView(String project, String file, CodeGraph graph, GraphNode node,
                             Integer maxEdges, Boolean includeExternal, Integer offset, ContentLevel contentLevel) {
        boolean withExternal = Boolean.TRUE.equals(includeExternal);
        Map<String, GraphNode> byId = graph.nodeById();
        List<GraphEdge> outEdges = graph.edgesBySource().getOrDefault(node.id(), List.of()).stream()
                .filter(e -> withExternal || !e.external())
                .toList();
        List<GraphEdge> inEdges = graph.edgesByTarget().getOrDefault(node.id(), List.of()).stream()
                .filter(e -> withExternal || !e.external())
                .toList();
        ContentLevel level = contentLevel == null ? ContentLevel.FULL : contentLevel;
        if (level != ContentLevel.FULL) {
            return new GraphView(project, file, node.id(), node.clusterId(), node.weight(),
                    List.of(), List.of(), List.of(), null, outEdges.size(), inEdges.size(),
                    aggregate(outEdges, false, byId), aggregate(inEdges, true, byId));
        }
        int cap = cap(maxEdges);
        int skip = offset == null || offset < 0 ? 0 : offset;
        List<GraphNeighbor> outgoing = outEdges.stream().skip(skip).limit(cap)
                .map(e -> neighbor(e.type(), e.target(), byId, e.external()))
                .toList();
        List<GraphNeighbor> incoming = inEdges.stream().skip(skip).limit(cap)
                .map(e -> neighbor(e.type(), e.source(), byId, e.external()))   // incoming: SOURCE, never target
                .toList();
        return new GraphView(project, file, node.id(), node.clusterId(), node.weight(),
                outgoing, incoming, List.of(), null, outEdges.size(), inEdges.size(), null, null);
    }

    private GraphNeighbor neighbor(EdgeType type, String id, Map<String, GraphNode> byId, boolean external) {
        GraphNode n = byId.get(id);
        return new GraphNeighbor(type, id, n != null ? n.file() : null, external);
    }

    /**
     * Collapses one direction's edges into module groups (efficiency measure 5): the
     * neighbour's file path yields the module (first path segment), method ids collapse
     * to their class, and each group carries its edge count, distinct-class count,
     * edge-type breakdown, and the most-referenced classes. Groups are sorted by edge
     * count descending (ties by module name) so the most important modules lead.
     */
    private List<GraphAggregate> aggregate(List<GraphEdge> edges, boolean incoming, Map<String, GraphNode> byId) {
        Map<String, List<GraphEdge>> byModule = new LinkedHashMap<>();
        for (GraphEdge e : edges) {
            String id = incoming ? e.source() : e.target();
            GraphNode n = byId.get(id);
            String module = moduleOf(n != null ? n.file() : null, e.external());
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(e);
        }
        return byModule.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue().size(), a.getValue().size());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .map(en -> {
                    List<GraphEdge> group = en.getValue();
                    Map<String, Integer> classCounts = new LinkedHashMap<>();
                    Map<EdgeType, Integer> typeCounts = new LinkedHashMap<>();
                    for (GraphEdge e : group) {
                        String id = incoming ? e.source() : e.target();
                        classCounts.merge(classNameOf(id), 1, Integer::sum);
                        typeCounts.merge(e.type(), 1, Integer::sum);
                    }
                    Map<EdgeType, Integer> typesSorted = typeCounts.entrySet().stream()
                            .sorted((x, y) -> {
                                int byCount = Integer.compare(y.getValue(), x.getValue());
                                return byCount != 0 ? byCount : x.getKey().name().compareTo(y.getKey().name());
                            })
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
                    List<GraphAggregate.ClassCount> top = classCounts.entrySet().stream()
                            .sorted((x, y) -> {
                                int byCount = Integer.compare(y.getValue(), x.getValue());
                                return byCount != 0 ? byCount : x.getKey().compareTo(y.getKey());
                            })
                            .limit(TOP_CLASSES_PER_MODULE)
                            .map(en2 -> new GraphAggregate.ClassCount(en2.getKey(), en2.getValue()))
                            .toList();
                    return new GraphAggregate(en.getKey(), group.size(), classCounts.size(), typesSorted, top);
                })
                .toList();
    }

    /** The module of a neighbour: the first path segment of its file, or a fallback label. */
    private String moduleOf(String file, boolean external) {
        if (external || file == null || file.isBlank()) {
            return EXTERNAL_MODULE;
        }
        String path = file.startsWith("/") ? file.substring(1) : file;
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(0, slash) : ROOT_MODULE;
    }

    /** A node id's class: method ids ({@code FQN#name}) collapse to their class FQN. */
    private String classNameOf(String id) {
        int hash = id.indexOf('#');
        return hash >= 0 ? id.substring(0, hash) : id;
    }

    private int cap(Integer maxEdges) {
        if (maxEdges == null || maxEdges < 1) {
            return DEFAULT_MAX_EDGES;
        }
        return Math.min(maxEdges, MAX_MAX_EDGES);
    }
}
