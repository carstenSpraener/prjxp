package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.TestPathFilter;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.PointerRenderer;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.retrieval.ranking.RankFusion;
import de.spraener.prjxp.common.retrieval.ranking.RankedItem;
import de.spraener.prjxp.common.retrieval.ranking.WeightedSignal;
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

    /** Rank-fusion weight of the semantic channel ({@code 0} disables it). */
    @Value("${prjxp.ranking.weight.vector:1.0}")
    double vectorWeight = 1.0;

    /** Rank-fusion weight of the exact-token channel ({@code 0} restores the pre-hybrid order). */
    @Value("${prjxp.ranking.weight.lexical:1.0}")
    double lexicalWeight = 1.0;

    /** Candidates for the exact-token channel; {@code null} uses the default adapter. */
    private LexicalSignalProvider lexicalSignalProvider = new LexicalSignalProvider();

    /** The configured content budget; callers that build their own SearchParams (the MCP tool) reuse it. */
    public int getMaxContentLength() {
        return maxContentLength;
    }

    /** The configured per-file chunk cap; callers that build their own SearchParams (the MCP tool) reuse it. */
    public int getMaxChunksPerFile() {
        return maxChunksPerFile;
    }

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
        return enrich(projectName, prompt, distance, maxResults, contentLevel, includeTests, vectorFilters,
                0, false, false);
    }

    /**
     * P2 variant: additionally renders a content snippet per pointer, de-duplicates chunk ids and
     * adds the {@code readChunks(ids=…)} hint. The defaults of the 7-arg overload keep the
     * historical P1 output byte-identical.
     */
    public String enrich(String projectName, String prompt, double distance, int maxResults,
                         ContentLevel contentLevel, boolean includeTests, Map<String, String> vectorFilters,
                         int snippetChars, boolean deduplicate, boolean readChunksHint) {
        EnrichmentOutcome outcome = enrichOutcome(projectName, prompt, distance, maxResults,
                contentLevel, includeTests, vectorFilters, snippetChars, deduplicate, readChunksHint);
        String fallbackInfo = "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)\n"
                .formatted(outcome.effectiveMinScore(), outcome.fallbackRounds());
        return fallbackInfo + outcome.context();
    }

    /**
     * Phase 02: the objective variant of the 10-arg {@link #enrich} — same parameter list, but
     * returns the {@link EnrichmentOutcome} instead of the header-prefixed string. The
     * {@code context} carries no header; the channel counts let a caller (the MCP tool) format
     * an honest one. The SearchParams are built with the historical defaults (floor 0.5,
     * no vector-hit requirement, grow-first), so REST callers keep their byte-identical output.
     */
    public EnrichmentOutcome enrichOutcome(String projectName, String prompt, double distance, int maxResults,
                                           ContentLevel contentLevel, boolean includeTests, Map<String, String> vectorFilters,
                                           int snippetChars, boolean deduplicate, boolean readChunksHint) {
        SearchParams params = new SearchParams(maxResults, distance);
        params.setContentLevel(contentLevel);
        params.setMaxContentLength(maxContentLength);
        params.setMaxChunksPerFile(maxChunksPerFile);
        params.setVectorFilters(vectorFilters == null ? Map.of() : Map.copyOf(vectorFilters));
        params.setSnippetChars(snippetChars);
        params.setDeduplicate(deduplicate);
        params.setReadChunksHint(readChunksHint);
        return enrichOutcome(projectName, prompt, List.of(),
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
        return enrichOutcome(projectName, prompt, prefetchedChunks, searchParams, iterationHandler,
                promptFormatter, includeTests, contextValidator).context();
    }

    /**
     * The core enrichment loop, returning the objective {@link EnrichmentOutcome} (Phase 02).
     * <p>
     * Validity: with {@code requireVectorHit=false} (default) the context validators decide,
     * exactly as before. With {@code requireVectorHit=true} a round is only valid when the
     * vector channel delivered hits — lexical (BM25) hits alone keep the threshold ladder
     * running until the floor, so a BM25 hit can no longer end the ladder early and be
     * labelled with a cosine threshold it never passed.
     * <p>
     * The {@code context} of the outcome carries no header: on success it is the formatted
     * prompt, on abort the historical "Es konnte kein valider Kontext erstellt werden! …"
     * message (REST contract). The channel counts are the hits of the final round after the
     * includeTests filter; prefetched chunks are a separate input and never count as vector hits.
     */
    public EnrichmentOutcome enrichOutcome(String projectName, String prompt,
                                           List<PxChunk> prefetchedChunks,
                                           SearchParams searchParams,
                                           Function<SearchParams, SearchParams> iterationHandler,
                                           Function<String, String> promptFormatter,
                                           boolean includeTests,
                                           Function<String, Boolean>... contextValidator) {
        boolean invalidPrompt = true;
        String overallContext = "";
        RetrievalSplit split = new RetrievalSplit(List.of(), List.of(), List.of());
        do {
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
            PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
            split = findRelevantSplit(chunkDao, prompt, searchParams.getMaxResult(), searchParams.getMinScore(), searchParams.getVectorFilters());
            List<ScoredChunk> relevantChunks = new ArrayList<>();
            relevantChunks.addAll(prefetchedChunks.stream().map(c -> new ScoredChunk(c, 1.0)).toList());
            relevantChunks.addAll(split.fused());
            if (!includeTests) {
                relevantChunks.removeIf(sc -> TestPathFilter.isTestPath(sc.chunk().getFile()));
            }
            if (searchParams.isDeduplicate()) {
                relevantChunks = dedupById(relevantChunks);
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
            boolean validatorsPass;
            if (contextValidator != null && contextValidator.length > 0) {
                validatorsPass = true;
                for (var pv : contextValidator) {
                    validatorsPass &= pv.apply(overallContext);
                }
            } else {
                validatorsPass = relevantChunks.size() > 0;
            }
            // The vector channel is judged after the includeTests filter: with includeTests=false
            // a pure test hit is not a usable vector hit, so it must not end the ladder early.
            invalidPrompt = (searchParams.isRequireVectorHit() && channelCount(split.vector(), includeTests) == 0) || !validatorsPass;
            if (invalidPrompt) {
                searchParams = iterationHandler.apply(searchParams);
            }
            if (searchParams.isAbort()) {
                String abortMessage = "Es konnte kein valider Kontext erstellt werden! " +
                        "Effektive Similarity-Schwelle: %.2f (%d Fallbacks)."
                                .formatted(searchParams.getEffectiveMinScore(), searchParams.getFallbackRounds());
                return new EnrichmentOutcome(abortMessage,
                        channelCount(split.vector(), includeTests),
                        channelCount(split.lexical(), includeTests),
                        bestScore(split.vector(), includeTests),
                        searchParams.getEffectiveMinScore(), searchParams.getFallbackRounds(), true);
            }
        } while (invalidPrompt);

        return new EnrichmentOutcome(promptFormatter.apply(overallContext),
                channelCount(split.vector(), includeTests),
                channelCount(split.lexical(), includeTests),
                bestScore(split.vector(), includeTests),
                searchParams.getEffectiveMinScore(), searchParams.getFallbackRounds(), false);
    }

    /**
     * The hits of one channel after the includeTests filter — the counts the outcome reports.
     * Prefetched chunks are a separate input and never count as vector hits.
     */
    private static int channelCount(List<ScoredChunk> channel, boolean includeTests) {
        if (includeTests) {
            return channel.size();
        }
        return (int) channel.stream().filter(sc -> !TestPathFilter.isTestPath(sc.chunk().getFile())).count();
    }

    /**
     * Phase 03b: the best (maximum) score of the channel after the includeTests filter — the
     * value the MCP confidence gate compares against. {@code 0.0} when no hit remains.
     */
    private static double bestScore(List<ScoredChunk> channel, boolean includeTests) {
        return channel.stream()
                .filter(sc -> includeTests || !TestPathFilter.isTestPath(sc.chunk().getFile()))
                .mapToDouble(ScoredChunk::score)
                .max()
                .orElse(0.0);
    }

    /**
     * Phase 02: the two channels of the hybrid retrieval, kept apart. The semantic channel is
     * thresholded (the cosine calibration stays a vector property); the exact-token channel is
     * never thresholded (a token hit is a fact, not a similarity). {@code fused} is the RRF
     * ranking of both — the rendered order; {@code vector}/{@code lexical} are the raw channel
     * hits, so a caller can tell which channel a hit came from.
     */
    private record RetrievalSplit(List<ScoredChunk> vector, List<ScoredChunk> lexical, List<ScoredChunk> fused) {
    }

    private RetrievalSplit findRelevantSplit(PxChunkDao chunkDao, String prompt, int maxResults, double minScore,
                                             Map<String, String> vectorFilters) {
        int retrievalWindow = Math.max(maxResults, vectorWindow);
        List<ScoredChunk> vectorHits = vectorHits(chunkDao, prompt, maxResults, minScore, vectorFilters, retrievalWindow);
        List<ScoredChunk> lexicalHits = lexicalSignalProvider.candidates(chunkDao, prompt, vectorFilters, retrievalWindow);
        List<ScoredChunk> fused;
        if (lexicalHits.isEmpty() || lexicalWeight <= 0.0) {
            fused = vectorHits;   // legacy order when the second channel is off or empty
        } else {
            Map<String, ScoredChunk> byId = new LinkedHashMap<>();
            for (ScoredChunk sc : vectorHits) {
                addById(byId, sc);
            }
            for (ScoredChunk sc : lexicalHits) {
                addById(byId, sc);
            }
            List<RankedItem> rankedItems = RankFusion.DEFAULT.rankItems(List.of(
                    new WeightedSignal("vector", vectorWeight, items(vectorHits)),
                    new WeightedSignal("lexical", lexicalWeight, items(lexicalHits))), retrievalWindow);
            fused = new ArrayList<>(rankedItems.size());
            for (RankedItem item : rankedItems) {
                ScoredChunk sc = byId.get(item.id());
                if (sc != null) {
                    fused.add(sc);
                }
            }
        }
        return new RetrievalSplit(vectorHits, lexicalHits, fused);
    }

    /**
     * The fused ranking of both channels — kept as a wrapper for the historical call shape.
     */
    private List<ScoredChunk> findRelevantScored(PxChunkDao chunkDao, String prompt, int maxResults, double minScore,
                                                  Map<String, String> vectorFilters) {
        return findRelevantSplit(chunkDao, prompt, maxResults, minScore, vectorFilters).fused();
    }

    private List<ScoredChunk> vectorHits(PxChunkDao chunkDao, String prompt, int maxResults, double minScore,
                                         Map<String, String> vectorFilters, int retrievalWindow) {
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

    private static List<RankedItem> items(List<ScoredChunk> chunks) {
        List<RankedItem> items = new ArrayList<>(chunks.size());
        for (ScoredChunk sc : chunks) {
            PxChunk chunk = sc.chunk();
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            items.add(new RankedItem(chunk.getId(), chunk.getFile(), sc.score()));
        }
        return items;
    }

    private static void addById(Map<String, ScoredChunk> byId, ScoredChunk sc) {
        String id = sc.chunk() == null ? null : sc.chunk().getId();
        if (id == null || id.isBlank()) {
            return;
        }
        byId.putIfAbsent(id, sc);
    }

    /**
     * P2: one entry per chunk id, keeping the highest score. Multiple retrievers can surface the
     * same chunk; the duplicate pointer blocks cost context without adding information.
     */
    private static List<ScoredChunk> dedupById(List<ScoredChunk> chunks) {
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        for (ScoredChunk sc : chunks) {
            String id = sc.chunk() == null ? null : sc.chunk().getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            byId.merge(id, sc, (a, b) -> b.score() > a.score() ? b : a);
        }
        return new ArrayList<>(byId.values());
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

    /**
     * One step of the threshold ladder.
     * <p>
     * Historical order (defaults: {@code growMaxResult=true}, {@code lowerThresholdFirst=false}):
     * grow {@code maxResult} first, then lower the threshold — the legacy rule lowered
     * unconditionally once {@code maxResult} was exhausted, and the floor (default 0.5) only
     * decides the abort, so the old 0.50 → 0.45 → abort sequence stays byte-identical.
     * <p>
     * Tool order (Phase 02: {@code lowerThresholdFirst=true}): the threshold descends first and
     * stops at the floor; {@code maxResult} only grows when the floor is already reached. With
     * {@code growMaxResult=false} and {@code start == floor} there is no movement left, so the
     * ladder aborts immediately instead of looping.
     */
    public SearchParams reIterate(SearchParams searchParams) {
        double minScoreBefore = searchParams.getMinScore();
        double floor = searchParams.getMinScoreFloor();
        boolean canGrow = searchParams.isGrowMaxResult() && searchParams.getMaxResult() < 16;
        if (searchParams.isLowerThresholdFirst()) {
            boolean canLower = searchParams.getMinScore() > floor + 1e-9;
            if (canLower) {
                lowerToNextGridPoint(searchParams);
            } else if (canGrow) {
                searchParams.setMaxResult(searchParams.getMaxResult() + 2);
            } else {
                searchParams.setAbort(true);
            }
        } else {
            if (canGrow) {
                searchParams.setMaxResult(searchParams.getMaxResult() + 2);
            } else {
                lowerToNextGridPoint(searchParams);
            }
        }
        if (searchParams.getMinScore() < minScoreBefore) {
            searchParams.setFallbackRounds(searchParams.getFallbackRounds() + 1);
            searchParams.setEffectiveMinScore(searchParams.getMinScore());
        }
        if (searchParams.getMinScore() < floor) {
            searchParams.setAbort(true);
        }
        return searchParams;
    }

    /**
     * Snap to the canonical 0.05 grid: next attempt is the highest grid point strictly below
     * the current threshold (e.g. 0.93 → 0.90, 0.85 → 0.80). The epsilon keeps on-grid values
     * strictly descending (avoids an infinite loop); recomputing from the current value avoids
     * accumulated float drift.
     */
    private static void lowerToNextGridPoint(SearchParams searchParams) {
        searchParams.setMinScore(
                Math.floor((searchParams.getMinScore() - 1e-9) / 0.05) * 0.05);
    }
}
