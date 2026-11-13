package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.TestPathFilter;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.PointerRenderer;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GRPromptEnrichment {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final RetrieverRegistry retrieverRegistry;
    @Value("${prjxp.gldrtrvr.maxcontentlength:50000}")
    private int maxContentLength = 50000;
    @Value("${prjxp.gldrtrvr.vector-window:50}")
    private int vectorWindow = 50;
    @Value("${prjxp.gldrtrvr.totalcontentlength:60000}")
    private int totalContentLength = 60000;
    /**
     * Efficiency measure 4 — maximum number of chunks kept per distinct file. A long document
     * split into many chunks would otherwise flood the context with near-duplicate pointer
     * blocks; only the highest-scoring chunks of each file are kept. {@code <= 0} disables it.
     */
    @Value("${prjxp.gldrtrvr.maxchunksperfile:3}")
    private int maxChunksPerFile = 3;

    public String enrich(String projectName, String prompt) {
        return enrich(projectName, prompt, true);
    }

    public String enrich(String projectName, String prompt, boolean includeTests) {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(maxContentLength);
        params.setMaxChunksPerFile(maxChunksPerFile);
        return enrich(projectName, prompt, List.of(),
                params,
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information aus dem Projekt '%s':
                                %s
                                
                                """, projectName, context
                        ),
                includeTests,
                c -> c.length() > 0);
    }

    public String enrich(String projectName, String prompt, double distance, int maxResults, boolean skeletonsOnly) {
        return enrich(projectName, prompt, distance, maxResults, skeletonsOnly, true);
    }

    public String enrich(String projectName, String prompt, double distance, int maxResults, boolean skeletonsOnly, boolean includeTests) {
        SearchParams params = new SearchParams(maxResults, distance, skeletonsOnly);
        params.setMaxContentLength(maxContentLength);
        params.setMaxChunksPerFile(maxChunksPerFile);
        String enrichedContext = enrich(projectName, prompt, List.of(),
                params,
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information in '%s':
                                %s
                                
                                """, projectName, context
                        ),
                includeTests,
                c -> c.length() > 0);
        String fallbackInfo = "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)\n"
                .formatted(params.getEffectiveMinScore(), params.getFallbackRounds());
        return fallbackInfo + enrichedContext;
    }

    public String enrich(String projectName, String prompt, double distance, int maxResults,
                         ContentLevel contentLevel, boolean includeTests) {
        return enrich(projectName, prompt, distance, maxResults, contentLevel, includeTests, Map.of());
    }

    public String enrich(String projectName, String prompt, double distance, int maxResults,
                         ContentLevel contentLevel, boolean includeTests, Map<String, String> vectorFilters) {
        SearchParams params = new SearchParams(maxResults, distance);
        params.setContentLevel(contentLevel);
        params.setMaxContentLength(maxContentLength);
        params.setMaxChunksPerFile(maxChunksPerFile);
        params.setVectorFilters(vectorFilters == null ? Map.of() : Map.copyOf(vectorFilters));
        String enrichedContext = enrich(projectName, prompt, List.of(),
                params,
                this::reIterate,
                (context) ->
                        String.format("""
                                Relevante Information in '%s':
                                %s

                                """, projectName, context
                        ),
                includeTests,
                c -> c.length() > 0);
        String fallbackInfo = "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)\n"
                .formatted(params.getEffectiveMinScore(), params.getFallbackRounds());
        return fallbackInfo + enrichedContext;
    }

    public String enrich(String projectName, String prompt, List<PxChunk> prefetchedChunks,
                         Function<String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        SearchParams params = new SearchParams(8, 0.85);
        params.setMaxContentLength(maxContentLength);
        params.setMaxChunksPerFile(maxChunksPerFile);
        return enrich(projectName, prompt, prefetchedChunks,
                params,
                this::reIterate,
                promptFormatter,
                true,
                contextValidator);
    }

    public String enrich(String projectName, String prompt,
                         List<PxChunk> prefetchedChunks,
                         SearchParams searchParams,
                         Function<SearchParams, SearchParams> iterationHandler,
                         Function<String, String> promptFormatter,
                         Function<String, Boolean>... contextValidator) {
        return enrich(projectName, prompt, prefetchedChunks, searchParams, iterationHandler,
                promptFormatter, true, contextValidator);
    }

    public String enrich(String projectName, String prompt,
                         List<PxChunk> prefetchedChunks,
                         SearchParams searchParams,
                         Function<SearchParams, SearchParams> iterationHandler,
                         Function<String, String> promptFormatter,
                         boolean includeTests,
                         Function<String, Boolean>... contextValidator) {
        boolean invalidPrompt = true;
        String overallContext = "";
        do {
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
            PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
            List<ScoredChunk> similarChunks = findRelevantScored(chunkDao, prompt, searchParams.getMaxResult(), searchParams.getMinScore(), searchParams.getVectorFilters());
            List<ScoredChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks.stream().map(c -> new ScoredChunk(c, 1.0)).toList());
            relevantChunks.addAll(similarChunks);
            if (!includeTests) {
                relevantChunks.removeIf(sc -> TestPathFilter.isTestPath(sc.chunk().getFile()));
            }
            relevantChunks = capByFile(relevantChunks, searchParams.getMaxResult(), searchParams.getMaxChunksPerFile());

            StringBuilder sb = new StringBuilder();
            ContentLevel level = searchParams.effectiveContentLevel();
            if (level == ContentLevel.NAMES || level == ContentLevel.AGGREGATED) {
                // Efficiency measure 1: filenames-only — one line per distinct file, no pointer
                // block, no content. Bypasses the per-retriever loop (and its content budget).
                // Efficiency measure 5: AGGREGATED is an alias for NAMES in the enrichment.
                sb.append(PointerRenderer.renderNames(relevantChunks));
            } else {
                int skippedRetrievers = 0;
                for( var gr : retrieverRegistry.all() ) {
                    String part = gr.buildPromptForFindings(projectName, relevantChunks, searchParams, contextValidator).toString();
                    // Global budget across all retrievers: the first output always goes in,
                    // subsequent ones only if they still fit (prevents mega-dumps).
                    if (sb.length() > 0 && sb.length() + part.length() > totalContentLength) {
                        skippedRetrievers++;
                        continue;
                    }
                    sb.append(part);
                }
                if (skippedRetrievers > 0) {
                    sb.append("\n[weitere ").append(skippedRetrievers).append(" Retriever-Outputs wegen Groessenlimit nicht enthalten]\n");
                }
            }
            overallContext = sb.toString();
            if (contextValidator != null && contextValidator.length > 0) {
                invalidPrompt = false;
                for (var pv : contextValidator) {
                    invalidPrompt |= !pv.apply(overallContext);
                }
            } else {
                invalidPrompt = relevantChunks.size() == 0;
            }
            if (invalidPrompt) {
                searchParams = iterationHandler.apply(searchParams);
            }
            if (searchParams.isAbort()) {
                return "Es konnte kein valider Kontext erstellt werden! " +
                        "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)."
                                .formatted(searchParams.getEffectiveMinScore(), searchParams.getFallbackRounds());
            }
        } while (invalidPrompt);

        return promptFormatter.apply(overallContext.toString());
    }

    private List<ScoredChunk> findRelevantScored(PxChunkDao chunkDao, String prompt, int maxResults, double minScore,
                                                  Map<String, String> vectorFilters) {
        int retrievalWindow = Math.max(maxResults, vectorWindow);
        try {
            return chunkDao.searchVector(prompt, vectorFilters, retrievalWindow).stream()
                    .filter(sc -> sc.score() >= minScore)
                    .toList();
        } catch (UnsupportedOperationException ignore) {
            return chunkDao.findRelevant(prompt, maxResults, minScore).stream()
                    .map(c -> new ScoredChunk(c, 1.0))
                    .toList();
        }
    }

    /**
     * Keeps at most {@code maxFiles} distinct files (ranked by best chunk score) and, within the
     * kept files, at most {@code maxChunksPerFile} chunks per file (the highest-scoring ones).
     * <p>
     * Efficiency measure 4 — the vectorSearch dedup: a long document split into many chunks must
     * not flood the context with near-duplicate pointer blocks. A {@code maxChunksPerFile <= 0}
     * disables the per-file cap (legacy behaviour: keep every chunk of the kept files).
     */
    private static List<ScoredChunk> capByFile(List<ScoredChunk> chunks, int maxFiles, int maxChunksPerFile) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        List<ScoredChunk> fileCapped = capFiles(chunks, maxFiles);
        return maxChunksPerFile > 0 ? capChunksPerFile(fileCapped, maxChunksPerFile) : fileCapped;
    }

    /** Keeps at most {@code maxFiles} distinct files (ranked by best chunk score) but never splits a file. */
    private static List<ScoredChunk> capFiles(List<ScoredChunk> chunks, int maxFiles) {
        if (maxFiles <= 0 || chunks.isEmpty()) {
            return chunks;
        }
        Map<String, Double> bestScoreByFile = new LinkedHashMap<>();
        for (ScoredChunk sc : chunks) {
            bestScoreByFile.merge(fileKey(sc), sc.score(), Math::max);
        }
        if (bestScoreByFile.size() <= maxFiles) {
            return chunks;
        }
        Set<String> kept = bestScoreByFile.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxFiles)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return chunks.stream().filter(sc -> kept.contains(fileKey(sc))).toList();
    }

    /**
     * Keeps at most {@code maxChunksPerFile} chunks per distinct file, preserving the input order.
     * The input is expected to be in descending score order, so the first chunks of a file are its
     * highest-scoring (most relevant) ones.
     */
    private static List<ScoredChunk> capChunksPerFile(List<ScoredChunk> chunks, int maxChunksPerFile) {
        Map<String, Integer> countByFile = new LinkedHashMap<>();
        List<ScoredChunk> result = new ArrayList<>();
        for (ScoredChunk sc : chunks) {
            String key = fileKey(sc);
            int count = countByFile.getOrDefault(key, 0);
            if (count < maxChunksPerFile) {
                result.add(sc);
                countByFile.put(key, count + 1);
            }
        }
        return result;
    }

    private static String fileKey(ScoredChunk sc) {
        String file = sc.chunk().getFile();
        return file == null ? "<unknown>" : file;
    }

    public SearchParams reIterate(SearchParams searchParams) {
        double minScoreBefore = searchParams.getMinScore();
        if (searchParams.getMaxResult() < 16) {
            searchParams.setMaxResult(searchParams.getMaxResult() + 2);
        } else {
            // Snap to the canonical 0.05 grid: next attempt is the highest grid point
            // strictly below the current threshold (e.g. 0.93 -> 0.90, 0.85 -> 0.80).
            // The epsilon keeps on-grid values strictly descending (avoids an infinite loop);
            // recomputing from the current value avoids accumulated float drift.
            searchParams.setMinScore(
                    Math.floor((searchParams.getMinScore() - 1e-9) / 0.05) * 0.05);
        }
        if (searchParams.getMinScore() < minScoreBefore) {
            searchParams.setFallbackRounds(searchParams.getFallbackRounds() + 1);
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
        }
        if (searchParams.getMinScore() < 0.5) {
            searchParams.setAbort(true);
        }
        return searchParams;
    }
}
