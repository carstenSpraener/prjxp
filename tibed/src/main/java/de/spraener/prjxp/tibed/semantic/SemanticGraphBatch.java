package de.spraener.prjxp.tibed.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.language.LeidenWeighter;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SemanticGraphSidecar;
import de.spraener.prjxp.common.model.WeightedEdge;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log
public class SemanticGraphBatch {

    private static final String NODE_ID_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_NODE_ID;
    private static final String CLUSTER_ID_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID;

    private final SemanticEdgeBuilder edgeBuilder;
    private final LeidenWeighter leidenWeighter;
    private final PrjXPConfig config;
    private final ObjectMapper objectMapper;

    public void run(String project, LuceneEmbeddingStore store) throws IOException {
        PrjXPConfig.SemanticGraphConfig graphConfig = config.getSemanticGraph();
        if (graphConfig.isCalibrate()) {
            calibrateGamma(project, store, graphConfig);
            return;
        }

        List<VectorChunk> projectChunks = store.readAllVectors(projectFilter(project));
        List<VectorChunk> input = edgeBuilder.selectInputChunks(projectChunks);
        EdgeConfig edgeConfig = new EdgeConfig(
                graphConfig.getK(), graphConfig.getThreshold(), graphConfig.isRequireMutual());
        List<SemanticEdge> edges = input.isEmpty()
                ? List.of()
                : edgeBuilder.buildSemanticEdges(store, project, input, edgeConfig);
        List<String> nodeIds = input.stream().map(VectorChunk::nodeId).toList();
        List<WeightedEdge> weightedEdges = edges.stream()
                .map(edge -> new WeightedEdge(edge.source(), edge.target(), edge.strength()))
                .toList();
        Map<String, String> clusterByNode = input.isEmpty()
                ? Map.of()
                : leidenWeighter.clusterWeighted(nodeIds, weightedEdges, graphConfig.getGamma());
        Map<String, Long> clusterSizes = clusterByNode.values().stream()
                .collect(Collectors.groupingBy(cluster -> cluster, Collectors.counting()));

        List<VectorChunk> updates = new ArrayList<>(projectChunks.size());
        for (VectorChunk chunk : projectChunks) {
            String clusterId = clusterByNode.get(chunk.nodeId());
            if (clusterId == null || clusterSizes.getOrDefault(clusterId, 0L) < 2) {
                clusterId = null;
            }

            Map<String, String> metadataPatch = new HashMap<>();
            metadataPatch.put(NODE_ID_FIELD, chunk.nodeId());
            metadataPatch.put(CLUSTER_ID_FIELD, clusterId);
            updates.add(new VectorChunk(chunk.nodeId(), chunk.id(), chunk.project(), chunk.part(),
                    null, null, metadataPatch));
        }

        store.upsertAll(updates);
        writeSidecar(project, input.size(), graphConfig);
        log.info("Semantic graph for " + project + ": " + input.size() + " nodes, "
                + edges.size() + " edges, " + clusterSizes.size() + " clusters, "
                + updates.size() + " project documents refreshed");
    }

    private void calibrateGamma(
            String project, LuceneEmbeddingStore store, PrjXPConfig.SemanticGraphConfig graphConfig)
            throws IOException {
        List<VectorChunk> input = edgeBuilder.selectInputChunks(store.readAllVectors(projectFilter(project)));
        EdgeConfig edgeConfig = new EdgeConfig(
                graphConfig.getK(), graphConfig.getThreshold(), graphConfig.isRequireMutual());
        List<SemanticEdge> edges = input.isEmpty()
                ? List.of()
                : edgeBuilder.buildSemanticEdges(store, project, input, edgeConfig);
        List<String> nodeIds = input.stream().map(VectorChunk::nodeId).toList();
        List<WeightedEdge> weightedEdges = edges.stream()
                .map(edge -> new WeightedEdge(edge.source(), edge.target(), edge.strength()))
                .toList();

        log.info("Calibrating gamma for " + input.size() + " nodes and " + edges.size() + " edges");
        for (int step = 1; step <= 20; step++) {
            double gamma = step / 10.0;
            Map<String, String> clusters = leidenWeighter.clusterWeighted(nodeIds, weightedEdges, gamma);
            Map<String, Long> sizes = clusters.values().stream()
                    .collect(Collectors.groupingBy(cluster -> cluster, Collectors.counting()));
            long multiNodeClusters = sizes.values().stream().filter(size -> size > 1).count();
            long largestCluster = sizes.values().stream().mapToLong(Long::longValue).max().orElse(0);
            log.info(String.format("gamma=%.1f -> %d clusters (%d multi-node), max size %d",
                    gamma, sizes.size(), multiNodeClusters, largestCluster));
        }
    }

    private Filter projectFilter(String project) {
        return new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project);
    }

    private void writeSidecar(
            String project, int chunkCount, PrjXPConfig.SemanticGraphConfig graphConfig) throws IOException {
        SemanticGraphSidecar sidecar = new SemanticGraphSidecar(
                config.getEmbedding().getModelName(),
                graphConfig.getGamma(),
                graphConfig.getThreshold(),
                graphConfig.getK(),
                graphConfig.isRequireMutual(),
                chunkCount,
                Instant.now().toString());
        Path indexParent = Path.of(config.getEmbeddingStoreLucene().getIndexPath())
                .toAbsolutePath()
                .getParent();
        Path sidecarPath = indexParent.resolve("semantic-graph-" + project + ".json");
        Files.createDirectories(sidecarPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(sidecarPath.toFile(), sidecar);
    }
}
