package de.spraener.prjxp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SemanticGraphSidecar;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log
public class SemanticGraphService {

    private static final String CLUSTER_ID_FIELD =
            PxChunk.PXCHUNK_METADATA + "." + GraphMetadata.SEMANTIC_CLUSTER_ID;
    private static final String FILE_FIELD = PxChunk.PXCHUNK_FILE;
    private static final String MIME_FIELD = PxChunk.PXCHUNK_MIME_TYPE;
    private static final int DEFAULT_MAX_EDGES = 20;
    private static final int MAX_MAX_EDGES = 100;
    private static final int DEFAULT_TOP_N = 10;
    private static final int MAX_TOP_N = 50;
    private static final int TOP_MEMBERS_PER_CLUSTER = 3;
    private static final int PREVIEW_CHARS = 120;

    private final LuceneEmbeddingStore store;
    private final ProjectRegistry projectRegistry;
    private final PrjXPConfig config;
    private final ObjectMapper objectMapper;

    public SemanticClusterView semanticCluster(String clusterId, String project, Integer topN) {
        if (StringUtils.isBlank(clusterId)) {
            return SemanticClusterView.unavailable("Parameter 'clusterId' ist erforderlich.");
        }
        String error = ensureSearchableOrError(project);
        if (error != null) {
            return SemanticClusterView.unavailable(error);
        }

        String normalizedClusterId = clusterId.trim();
        Filter projectFilter = projectFilter(project);
        long totalMembers = store.countByMetadata(CLUSTER_ID_FIELD, projectFilter)
                .getOrDefault(normalizedClusterId, 0L);
        if (totalMembers == 0) {
            return SemanticClusterView.unavailable("Cluster nicht gefunden: " + normalizedClusterId);
        }

        int limit = cap(topN, DEFAULT_TOP_N, MAX_TOP_N);
        List<SemanticMember> members = store.searchByMetadata(
                        CLUSTER_ID_FIELD, normalizedClusterId, projectFilter, limit)
                .stream()
                .map(this::toMember)
                .toList();
        return new SemanticClusterView("semantic", project, normalizedClusterId,
                Math.toIntExact(totalMembers), members, null);
    }

    public SemanticNodeView semanticNode(String nodeId, String project, Integer maxEdges) {
        if (StringUtils.isBlank(nodeId)) {
            return SemanticNodeView.unavailable("Parameter 'nodeId' ist erforderlich.");
        }
        String error = ensureSearchableOrError(project);
        if (error != null) {
            return SemanticNodeView.unavailable(error);
        }

        String normalizedNodeId = nodeId.trim();
        try {
            Optional<VectorChunk> optionalChunk = store.readChunkById(normalizedNodeId, project);
            if (optionalChunk.isEmpty()) {
                return SemanticNodeView.unavailable("Chunk nicht gefunden: " + normalizedNodeId);
            }
            VectorChunk chunk = optionalChunk.get();
            int limit = cap(maxEdges, DEFAULT_MAX_EDGES, MAX_MAX_EDGES);
            double minScore = config.getSemanticGraph().getThreshold();
            List<EmbeddingMatch<TextSegment>> matches = store.searchKnn(
                    Embedding.from(chunk.vector()), limit + 1, minScore, projectFilter(project));
            List<SemanticNeighbor> neighbors = matches.stream()
                    .filter(match -> !match.embeddingId().equals(chunk.nodeId()))
                    .limit(limit)
                    .map(this::toNeighbor)
                    .toList();
            return new SemanticNodeView("semantic", project, chunk.nodeId(),
                    chunk.metadata().get(FILE_FIELD),
                    chunk.metadata().get(MIME_FIELD),
                    chunk.metadata().get(CLUSTER_ID_FIELD),
                    neighbors,
                    null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read semantic node " + normalizedNodeId, e);
        }
    }

    public SemanticOverviewView semanticOverview(String project, Integer topN) {
        String error = ensureSearchableOrError(project);
        if (error != null) {
            return SemanticOverviewView.unavailable(error);
        }

        Filter projectFilter = projectFilter(project);
        Map<String, Long> counts = store.countByMetadata(CLUSTER_ID_FIELD, projectFilter);
        int limit = cap(topN, DEFAULT_TOP_N, MAX_TOP_N);
        List<SemanticClusterSummary> clusters = counts.entrySet().stream()
                .sorted((first, second) -> {
                    int bySize = Long.compare(second.getValue(), first.getValue());
                    return bySize != 0 ? bySize : first.getKey().compareTo(second.getKey());
                })
                .limit(limit)
                .map(entry -> {
                    List<String> topFiles = store.searchByMetadata(
                                    CLUSTER_ID_FIELD, entry.getKey(), projectFilter, TOP_MEMBERS_PER_CLUSTER)
                            .stream()
                            .map(chunk -> chunk.metadata().get(FILE_FIELD))
                            .filter(Objects::nonNull)
                            .toList();
                    return new SemanticClusterSummary(
                            entry.getKey(), Math.toIntExact(entry.getValue()), topFiles);
                })
                .toList();
        String model = config.getEmbedding().getModelName();
        return new SemanticOverviewView("semantic", project, counts.size(), clusters,
                model, sidecarWarning(project, model), null);
    }

    private String ensureSearchableOrError(String project) {
        try {
            projectRegistry.ensureSearchable(project);
            return null;
        } catch (UnknownProjectException e) {
            return e.getMessage();
        }
    }

    private Filter projectFilter(String project) {
        return new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project);
    }

    private int cap(Integer requested, int defaultValue, int maxValue) {
        return requested == null || requested < 1 ? defaultValue : Math.min(requested, maxValue);
    }

    private SemanticMember toMember(VectorChunk chunk) {
        String content = chunk.content() == null ? "" : chunk.content();
        String preview = content.length() > PREVIEW_CHARS
                ? content.substring(0, PREVIEW_CHARS) + "…"
                : content;
        return new SemanticMember(chunk.nodeId(),
                chunk.metadata().get(FILE_FIELD),
                chunk.metadata().get(MIME_FIELD),
                preview);
    }

    private SemanticNeighbor toNeighbor(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Map<String, Object> metadata = segment == null ? Map.of() : segment.metadata().toMap();
        Object file = metadata.get(FILE_FIELD);
        Object mime = metadata.get(MIME_FIELD);
        return new SemanticNeighbor(match.embeddingId(),
                file == null ? null : file.toString(),
                mime == null ? null : mime.toString(),
                match.score());
    }

    private String sidecarWarning(String project, String currentModel) {
        Path indexParent = Path.of(config.getEmbeddingStoreLucene().getIndexPath())
                .toAbsolutePath()
                .getParent();
        Path sidecarPath = indexParent.resolve("semantic-graph-" + project + ".json");
        if (!Files.isRegularFile(sidecarPath)) {
            return "Semantic graph sidecar is missing; calibration freshness is unknown.";
        }
        try {
            SemanticGraphSidecar sidecar = objectMapper.readValue(
                    sidecarPath.toFile(), SemanticGraphSidecar.class);
            if (sidecar.model() != null && !sidecar.model().equals(currentModel)) {
                return "Index embedded with model '" + sidecar.model() + "' but the current model is '"
                        + currentModel + "' — clusters may be stale, re-run tibed.";
            }
            return null;
        } catch (IOException e) {
            return "Could not read semantic graph sidecar: " + e.getMessage();
        }
    }
}
