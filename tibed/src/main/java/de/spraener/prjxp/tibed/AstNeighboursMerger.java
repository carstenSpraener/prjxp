package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Merges the AST-neighbour sidecar written by chunk-norris (Phase 03) into chunk metadata.
 * <p>
 * The sidecar is code-graph data ({@code chunkId → [neighbourChunkIds]}), not a vector result:
 * it is available before embedding, so every writer of chunk embeddings (CLI {@link EmbeddingService},
 * hub export and hub live update) can stamp the same field. Semantic neighbours are a different
 * layer — they are computed from the stored vectors by {@code SemanticGraphBatch}.
 */
public final class AstNeighboursMerger {

    private AstNeighboursMerger() {
    }

    /** Writes the chunk's sidecar neighbours as a comma-separated list (no-op when absent). */
    public static void merge(PxChunk chunk, Map<String, List<String>> astNeighbours) {
        if (chunk == null || astNeighbours == null || astNeighbours.isEmpty()) {
            return;
        }
        List<String> neighbours = astNeighbours.get(chunk.getId());
        if (neighbours != null && !neighbours.isEmpty()) {
            chunk.getMetadata().put(SignatureMetadata.AST_NEIGHBOURS,
                    String.join(SignatureMetadata.LIST_SEPARATOR, neighbours));
        }
    }

    /** Applies {@link #merge(PxChunk, Map)} to every chunk of a batch. */
    public static void mergeAll(Collection<PxChunk> chunks, Map<String, List<String>> astNeighbours) {
        if (chunks == null || astNeighbours == null || astNeighbours.isEmpty()) {
            return;
        }
        chunks.forEach(chunk -> merge(chunk, astNeighbours));
    }
}
