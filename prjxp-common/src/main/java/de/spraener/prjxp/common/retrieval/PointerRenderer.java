package de.spraener.prjxp.common.retrieval;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the dual-graph reference block (paper §4.1) for one hit.
 * Stateless, without Spring — instantiable by every retriever.
 */
public final class PointerRenderer {
    public static final int MAX_KEYWORDS = 8;
    public static final int MAX_NEIGHBOURS = 10;

    /**
     * Renders the block for one hit. Missing fields are omitted.
     *
     * @param projectId name of the project (e.g. "prjxp")
     * @param chunk     the (combined) hit chunk
     * @param score     similarity score 0..1
     */
    public String render(String projectId, PxChunk chunk, double score) {
        return render(projectId, chunk, score, null);
    }

    /**
     * Renders the block for one hit, using {@code fallbackGraphNode} when the chunk carries no
     * {@code graph_node} metadata. Dependency chunks (Java/TypeScript) have neither a file nor a
     * graph node of their own — their {@code parent} is the type node they belong to.
     */
    public String render(String projectId, PxChunk chunk, double score, String fallbackGraphNode) {
        Map<String, String> metadata = chunk == null || chunk.getMetadata() == null
                ? Map.of()
                : chunk.getMetadata();
        String id = chunk == null ? null : chunk.getId();
        String file = chunk == null ? null : chunk.getFile();
        String graphNode = graphNode(metadata, fallbackGraphNode);
        String semanticNodeId = value(metadata, GraphMetadata.SEMANTIC_NODE_ID);

        StringBuilder block = new StringBuilder();
        line(block, "## " + heading(file, graphNode, id));
        line(block, "project_id: \"" + nullToEmpty(projectId) + "\"");
        line(block, "chunk_id: \"" + nullToEmpty(id) + "\"");
        line(block, "score: " + String.format(Locale.ROOT, "%.2f", score));
        if (isPresent(file)) {
            line(block, "file: \"" + file + "\"");
        }
        String chapter = chapter(graphNode, value(metadata, SignatureMetadata.HIERARCHY));
        if (chapter != null) {
            line(block, "chapter: \"" + chapter + "\"");
        }
        String clusterId = clusterId(metadata);
        if (clusterId != null) {
            line(block, "cluster_id: \"" + clusterId + "\"");
        }
        List<String> clusterKeywords = splitList(
                value(metadata, SignatureMetadata.CLUSTER_KEYWORDS), MAX_KEYWORDS);
        if (!clusterKeywords.isEmpty()) {
            line(block, "cluster_keywords: " + listLiteral(clusterKeywords));
        }
        List<String> keywords = splitList(value(metadata, SignatureMetadata.KEYWORDS), MAX_KEYWORDS);
        if (!keywords.isEmpty()) {
            line(block, "keywords: " + listLiteral(keywords));
        }
        List<String> neighbours = neighbours(metadata);
        if (!neighbours.isEmpty()) {
            line(block, "neighbours: " + listLiteral(neighbours));
        }
        if (isPresent(file)) {
            line(block, "→ readFile(\"" + file + "\")");
        }
        if (graphNode != null) {
            line(block, "→ " + readBySignature(graphNode));
            line(block, "→ graphNode(\"" + fqnOf(graphNode) + "\")");
        }
        if (isPresent(semanticNodeId)) {
            line(block, "→ semanticGraphNode(\"" + semanticNodeId + "\")");
        }
        return block.toString();
    }

    /**
     * Renders the minimal {@link ContentLevel#NAMES} enumeration for a list of scored chunks:
     * one line per distinct file path (or the parent type for file-less dependency chunks),
     * in first-seen order, joined by newlines.
     * <p>
     * Efficiency measure 1 — the "filenames-only" mode for {@code grep}/{@code vectorSearch}:
     * it answers "where is X used" with a bare file list, without the pointer block, so the
     * context stays small even for a class referenced from hundreds of files.
     */
    public static String renderNames(List<ScoredChunk> chunks) {
        Set<String> emitted = new LinkedHashSet<>();
        for (ScoredChunk sc : chunks) {
            PxChunk chunk = sc == null ? null : sc.chunk();
            if (chunk == null) {
                continue;
            }
            String file = chunk.getFile();
            String target = (file != null && !file.isBlank()) ? file : chunk.getParent();
            if (target != null && !target.isBlank()) {
                emitted.add(target);
            }
        }
        return String.join("\n", emitted);
    }

    private static String heading(String file, String graphNode, String id) {
        if (isPresent(file)) {
            return file;
        }
        if (graphNode != null) {
            return graphNode;
        }
        return nullToEmpty(id);
    }

    /** The chunk's own {@code graph_node}, else the caller-provided fallback (dependency chunks). */
    private static String graphNode(Map<String, String> metadata, String fallbackGraphNode) {
        String graphNode = value(metadata, GraphMetadata.GRAPH_NODE);
        if (graphNode != null) {
            return graphNode;
        }
        return isPresent(fallbackGraphNode) ? fallbackGraphNode.trim() : null;
    }

    private static String chapter(String graphNode, String hierarchy) {
        if (graphNode != null) {
            return graphNode;
        }
        return isPresent(hierarchy) ? hierarchy : null;
    }

    private static String clusterId(Map<String, String> metadata) {
        String structural = value(metadata, GraphMetadata.LEIDEN_CLUSTER_ID);
        return structural != null ? structural : value(metadata, GraphMetadata.SEMANTIC_CLUSTER_ID);
    }

    private static List<String> neighbours(Map<String, String> metadata) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(splitBy(value(metadata, SignatureMetadata.AST_NEIGHBOURS),
                SignatureMetadata.LIST_SEPARATOR, MAX_NEIGHBOURS));
        merged.addAll(splitBy(value(metadata, SignatureMetadata.SEMANTIC_NEIGHBOURS),
                SignatureMetadata.LIST_SEPARATOR, MAX_NEIGHBOURS));
        List<String> neighbours = new ArrayList<>(merged);
        return neighbours.size() > MAX_NEIGHBOURS ? neighbours.subList(0, MAX_NEIGHBOURS) : neighbours;
    }

    /** {@code FQN#name(params)} → {@code readBySignature("name", "FQN")}, otherwise {@code readBySignature("gn")}. */
    private static String readBySignature(String graphNode) {
        int hash = graphNode.indexOf('#');
        if (hash < 0) {
            return "readBySignature(\"" + graphNode + "\")";
        }
        String fqn = graphNode.substring(0, hash);
        String signature = graphNode.substring(hash + 1);
        int paren = signature.indexOf('(');
        String name = paren < 0 ? signature : signature.substring(0, paren);
        return "readBySignature(\"" + name + "\", \"" + fqn + "\")";
    }

    private static String fqnOf(String graphNode) {
        int hash = graphNode.indexOf('#');
        return hash < 0 ? graphNode : graphNode.substring(0, hash);
    }

    private static List<String> splitList(String value, int limit) {
        return splitBy(value, ",", limit);
    }

    /** Splits a separator-delimited list (chunk ids are not comma-safe). */
    private static List<String> splitBy(String value, String separator, int limit) {
        List<String> entries = new ArrayList<>();
        if (value == null) {
            return entries;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : value.split(Pattern.quote(separator))) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (unique.add(entry)) {
                entries.add(entry);
                if (entries.size() == limit) {
                    break;
                }
            }
        }
        return entries;
    }

    private static String listLiteral(List<String> entries) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                literal.append(", ");
            }
            literal.append('"').append(entries.get(i)).append('"');
        }
        return literal.append(']').toString();
    }

    private static String value(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        return isPresent(value) ? value.trim() : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void line(StringBuilder block, String content) {
        block.append(content).append('\n');
    }
}
