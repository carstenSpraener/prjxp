package de.spraener.prjxp.common.retrieval;

import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders the dual-graph reference block (paper §4.1) for one hit.
 * Stateless, without Spring — instantiable by every retriever.
 * <p>
 * The {@code POINTERS} block is deliberately minimal: identity ({@code chunk_id}, {@code score}),
 * optional document structure ({@code chapter}) and navigational pointers. Metadata such as
 * clusters, keywords and neighbours is not rendered here — it is available via {@code FULL}/
 * {@code SKELETON} or can be fetched on demand via {@code graphNode}.
 */
public final class PointerRenderer {
    /**
     * @deprecated the compact {@code POINTERS} block no longer renders keyword lists.
     */
    @Deprecated
    public static final int MAX_KEYWORDS = 8;
    /**
     * @deprecated the compact {@code POINTERS} block no longer renders neighbour lists.
     */
    @Deprecated
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
        return render(projectId, chunk, score, fallbackGraphNode, 1);
    }

    /**
     * Renders the block for one file. {@code hitCount} is the number of chunks of the same file
     * that were collapsed into this single block; it is only shown when greater than one.
     */
    public String render(String projectId, PxChunk chunk, double score, String fallbackGraphNode, int hitCount) {
        Map<String, String> metadata = chunk == null || chunk.getMetadata() == null
                ? Map.of()
                : chunk.getMetadata();
        String id = chunk == null ? null : chunk.getId();
        String file = chunk == null ? null : chunk.getFile();
        String graphNode = graphNode(metadata, fallbackGraphNode);

        StringBuilder block = new StringBuilder();
        line(block, "## " + heading(file, graphNode, id));
        line(block, "chunk_id: \"" + nullToEmpty(id) + "\"");
        line(block, "score: " + String.format(Locale.ROOT, "%.2f", score));
        if (hitCount > 1) {
            line(block, "hits: " + hitCount);
        }
        // Document chunks have no graph node; there the hierarchy is the only structure hint.
        // Code chunks carry their structure in graph_node and the pointer lines below.
        if (graphNode == null) {
            String chapter = value(metadata, SignatureMetadata.HIERARCHY);
            if (chapter != null) {
                line(block, "chapter: \"" + chapter + "\"");
            }
        }
        if (isPresent(file)) {
            line(block, "→ readFile(\"" + file + "\")");
        }
        if (graphNode != null) {
            line(block, "→ " + readBySignature(graphNode));
            line(block, "→ graphNode(\"" + fqnOf(graphNode) + "\")");
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
