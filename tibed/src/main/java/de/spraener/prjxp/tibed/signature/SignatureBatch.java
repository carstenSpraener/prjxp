package de.spraener.prjxp.tibed.signature;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.TfIdfSigner;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the null-LLM signature layer into the index: TF-IDF keywords per chunk and c-TF-IDF
 * keywords per cluster (paper §3.3). Runs for every chunk of the project (code and docs).
 */
@Service
@RequiredArgsConstructor
@Log
public class SignatureBatch {

    private static final String KEYWORDS_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + SignatureMetadata.KEYWORDS;
    private static final String CLUSTER_KEYWORDS_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + SignatureMetadata.CLUSTER_KEYWORDS;
    private static final String LEIDEN_CLUSTER_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.LEIDEN_CLUSTER_ID;
    private static final String SEMANTIC_CLUSTER_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID;

    private final PrjXPConfig config;

    /**
     * Computes TF-IDF keywords per chunk and c-TF-IDF keywords per cluster,
     * then writes them into the Lucene index via upsertAll.
     * Runs for ALL chunks in the project (code + docs).
     */
    public void run(String project, LuceneEmbeddingStore store) throws IOException {
        PrjXPConfig.SignatureConfig sigCfg = config.getSignature();
        if (sigCfg == null || !sigCfg.isEnabled()) return;

        TfIdfSigner signer = new TfIdfSigner(
                new TfIdfSigner.SignerConfig(sigCfg.getTopN(), sigCfg.getMinTermLength()));

        // 1. Keywords per chunk
        Map<String, List<String>> chunkKeywords = signer.signChunks(store, project);

        // 2. Cluster → member chunk IDs (from metadata in the index)
        List<VectorChunk> projectChunks = store.readAllVectors(
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project));
        Map<String, List<String>> clusterToChunkIds = buildClusterMap(projectChunks);
        Map<String, List<String>> clusterKeywords = signer.signClusters(store, project, clusterToChunkIds);

        // 3. Build upsert updates (null deletes the key in the Lucene document)
        List<VectorChunk> updates = new ArrayList<>(projectChunks.size());
        for (VectorChunk chunk : projectChunks) {
            Map<String, String> patch = new HashMap<>();
            patch.put(KEYWORDS_FIELD, joined(chunkKeywords.get(chunk.id())));

            String clusterId = clusterIdOf(chunk);
            patch.put(CLUSTER_KEYWORDS_FIELD,
                    clusterId == null ? null : joined(clusterKeywords.get(clusterId)));

            updates.add(new VectorChunk(chunk.nodeId(), chunk.id(), chunk.project(), chunk.part(),
                    null, null, patch));
        }
        store.upsertAll(updates);
        log.info("Signatures for " + project + ": " + chunkKeywords.size() + " chunks, "
                + clusterKeywords.size() + " clusters");
    }

    /** Builds clusterId → [chunkId, ...] from the index metadata (structural cluster wins over semantic). */
    private Map<String, List<String>> buildClusterMap(List<VectorChunk> chunks) {
        Map<String, List<String>> result = new HashMap<>();
        for (VectorChunk chunk : chunks) {
            String clusterId = clusterIdOf(chunk);
            if (clusterId != null) {
                result.computeIfAbsent(clusterId, key -> new ArrayList<>()).add(chunk.id());
            }
        }
        return result;
    }

    /** The chunk's structural (Leiden) cluster, falling back to its semantic cluster. */
    private String clusterIdOf(VectorChunk chunk) {
        String clusterId = chunk.metadata().get(LEIDEN_CLUSTER_FIELD);
        if (clusterId == null || clusterId.isBlank()) {
            clusterId = chunk.metadata().get(SEMANTIC_CLUSTER_FIELD);
        }
        return clusterId == null || clusterId.isBlank() ? null : clusterId;
    }

    private String joined(List<String> terms) {
        return terms == null || terms.isEmpty() ? null : String.join(",", terms);
    }
}
