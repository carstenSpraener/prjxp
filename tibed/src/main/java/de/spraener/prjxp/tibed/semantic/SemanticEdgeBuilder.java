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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
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

        Map<String, List<Neighbor>> topK = searchTopK(store, project, input, inputNodeIds, config);

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

    /**
     * Cap for the auto thread count. The flat brute-force kNN scan is <b>memory-bandwidth-bound</b>,
     * not CPU-bound: each query streams the whole vector store (~92 MB for 22.5k × 1024-d), so
     * beyond a few threads the memory subsystem saturates and every thread slows down. Measured on
     * an M5 Max (18 cores): 1→85 s, 2→68 s, 4→61 s, 8→135 s, 18→159 s. The sweet spot is a small
     * number of threads, so the auto default caps there instead of using all cores (which is the
     * <em>worst</em> choice). Override per machine via {@code parallelism} if its bandwidth differs.
     */
    private static final int AUTO_PARALLELISM_CAP = 4;

    /**
     * Runs one kNN search per input chunk. With {@code parallelism > 1} the searches run on a
     * fixed thread pool — the search is read-only and Lucene's {@code SearcherManager} is
     * thread-safe. Results are collected in input order, so the output is identical to a
     * sequential run.
     *
     * <p><b>Scaling note:</b> see {@link #AUTO_PARALLELISM_CAP} — the wall time does <em>not</em>
     * scale with the core count because the scan is bandwidth-bound. The elapsed time is logged so
     * the sweet spot can be re-calibrated per machine via {@code parallelism}.
     */
    private Map<String, List<Neighbor>> searchTopK(
            LuceneEmbeddingStore store, String project, List<VectorChunk> input,
            Set<String> inputNodeIds, EdgeConfig config) throws IOException {
        int parallelism = config.parallelism() <= 0
                ? Math.min(Runtime.getRuntime().availableProcessors(), AUTO_PARALLELISM_CAP)
                : config.parallelism();
        Map<String, List<Neighbor>> topK = new HashMap<>(Math.max(16, input.size() * 2));
        log.info("Semantic kNN search for " + project + ": " + input.size()
                + " queries on " + parallelism + " thread(s)");
        long startNanos = System.nanoTime();
        if (parallelism <= 1 || input.size() <= 1) {
            for (VectorChunk chunk : input) {
                topK.put(chunk.nodeId(),
                        searchNeighbours(store, project, chunk, inputNodeIds, config));
            }
        } else {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger sequence = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "semantic-knn-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            };
            try (ExecutorService pool = Executors.newFixedThreadPool(parallelism, factory)) {
                List<Future<List<Neighbor>>> futures = new ArrayList<>(input.size());
                for (VectorChunk chunk : input) {
                    futures.add(pool.submit(
                            () -> searchNeighbours(store, project, chunk, inputNodeIds, config)));
                }
                for (int i = 0; i < input.size(); i++) {
                    try {
                        topK.put(input.get(i).nodeId(), futures.get(i).get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while building semantic edges", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IOException io) {
                            throw io;
                        }
                        throw new IOException(
                                "kNN search failed for " + input.get(i).nodeId(), cause);
                    }
                }
            }
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Semantic kNN search for " + project + " done in " + elapsedMs
                + " ms (" + String.format("%.1f", input.size() * 1000.0 / Math.max(1, elapsedMs))
                + " queries/s)");
        return topK;
    }

    /** One kNN search plus the input/mutual pre-filtering for a single chunk. */
    private List<Neighbor> searchNeighbours(
            LuceneEmbeddingStore store, String project, VectorChunk chunk,
            Set<String> inputNodeIds, EdgeConfig config) throws IOException {
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
        return neighbors;
    }

    private boolean isCodeCode(String first, String second, Map<String, Boolean> isDoc) {
        return !isDoc.getOrDefault(first, false) && !isDoc.getOrDefault(second, false);
    }

    private boolean isMutual(String first, String second, Map<String, List<Neighbor>> topK) {
        return topK.getOrDefault(second, List.of()).stream()
                .anyMatch(neighbor -> neighbor.nodeId().equals(first));
    }
}
