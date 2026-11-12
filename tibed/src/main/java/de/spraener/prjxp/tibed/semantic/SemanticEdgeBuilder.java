package de.spraener.prjxp.tibed.semantic;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Log
@Component
public class SemanticEdgeBuilder {

    private static final String MIME_KEY = PxChunk.PXCHUNK_MIME_TYPE;
    private static final String JAVA_SECTION_KEY = PxChunk.PXCHUNK_METADATA + ".java_code_section";
    private static final String TS_SECTION_KEY = PxChunk.PXCHUNK_METADATA + ".typescript_code_section";
    private static final String CLASS_FRAME = "classFrame";
    private static final Set<String> DOC_MIMES =
            Set.of("text/markdown", "application/pdf", "text/plain");
    private static final String JAVA_MIME = "text/x-java-code";
    private static final String TS_MIME = "text/x-typescript-code";

    private record Neighbor(String nodeId, double score) {}

    public List<VectorChunk> selectInputChunks(List<VectorChunk> chunks) {
        return chunks.stream().filter(this::isLeidenInput).toList();
    }

    private boolean isLeidenInput(VectorChunk chunk) {
        String mime = chunk.metadata().get(MIME_KEY);
        if (mime == null) {
            return false;
        }
        if (DOC_MIMES.contains(mime)) {
            return true;
        }
        if (JAVA_MIME.equals(mime)) {
            return CLASS_FRAME.equals(chunk.metadata().get(JAVA_SECTION_KEY));
        }
        return TS_MIME.equals(mime)
                && CLASS_FRAME.equals(chunk.metadata().get(TS_SECTION_KEY));
    }

    public List<SemanticEdge> buildSemanticEdges(
            LuceneEmbeddingStore store, String project, List<VectorChunk> input, EdgeConfig config)
            throws IOException {
        Set<String> inputNodeIds = input.stream()
                .map(VectorChunk::nodeId)
                .collect(Collectors.toSet());
        Map<String, Boolean> isDoc = new HashMap<>();
        for (VectorChunk chunk : input) {
            isDoc.put(chunk.nodeId(), DOC_MIMES.contains(chunk.metadata().get(MIME_KEY)));
        }

        Map<String, List<Neighbor>> topK = new HashMap<>();
        for (VectorChunk chunk : input) {
            List<EmbeddingMatch<TextSegment>> matches = store.searchKnn(
                    Embedding.from(chunk.vector()),
                    config.k(),
                    config.minScore(),
                    new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project));
            List<Neighbor> neighbors = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                String neighborId = match.embeddingId();
                if (neighborId.equals(chunk.nodeId()) || !inputNodeIds.contains(neighborId)) {
                    continue;
                }
                if (match.score() < config.minScore()) {
                    continue;
                }
                neighbors.add(new Neighbor(neighborId, match.score()));
            }
            topK.put(chunk.nodeId(), neighbors);
        }

        Map<String, SemanticEdge> edges = new LinkedHashMap<>();
        for (VectorChunk chunk : input) {
            for (Neighbor neighbor : topK.getOrDefault(chunk.nodeId(), List.of())) {
                String neighborId = neighbor.nodeId();
                if (isCodeCode(chunk.nodeId(), neighborId, isDoc)) {
                    continue;
                }
                if (config.requireMutual() && !isMutual(chunk.nodeId(), neighborId, topK)) {
                    continue;
                }
                String source = chunk.nodeId().compareTo(neighborId) < 0
                        ? chunk.nodeId()
                        : neighborId;
                String target = chunk.nodeId().compareTo(neighborId) < 0
                        ? neighborId
                        : chunk.nodeId();
                String key = source + '\u0000' + target;
                edges.putIfAbsent(key, new SemanticEdge(source, target, neighbor.score()));
            }
        }
        return new ArrayList<>(edges.values());
    }

    private boolean isCodeCode(String first, String second, Map<String, Boolean> isDoc) {
        return !isDoc.getOrDefault(first, false) && !isDoc.getOrDefault(second, false);
    }

    private boolean isMutual(String first, String second, Map<String, List<Neighbor>> topK) {
        return topK.getOrDefault(second, List.of()).stream()
                .anyMatch(neighbor -> neighbor.nodeId().equals(first));
    }
}
