package de.spraener.prjxp.gldrtrvr.text;

import de.spraener.prjxp.common.model.DocWindower;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * Retriever for {@code text/plain} chunks produced by the
 * {@code DefaultLineBreakTextChunker} (the line-break text fallback).
 * <p>
 * Unlike the language-specific retrievers (Java/TS/VB) and the {@code MarkdownRetriever}
 * (which rebuild a document tree), this retriever presents the content as-is: each
 * (recombined) file is rendered as a plain-text excerpt. The parts of a file share the same
 * ID, so they are recombined via {@link PxChunk#combine} before presentation — the whole
 * file (or the parts that were retrieved) is shown, not individual fragments.
 * <p>
 * Because the chunks carry the {@link PxChunk#TEXT_MARKER} (not {@link PxChunk#FULLTEXT_MARKER}),
 * they are <b>not</b> excluded by the {@code != FULLTEXT} filter in the search services and
 * therefore reach this retriever via {@code vectorSearch}/{@code grep}.
 */
@Service
@RequiredArgsConstructor
public class TextRetriever implements GoldenRetriever {
    public static final String MIME = "text/plain";
    private final PxChunkDaoProvider chunkDaoProvider;

    /** Max chars a non-code (doc) chunk may have before it is windowed. Initialized with the
     *  property default so that non-Spring construction (tests) behaves like the configured default. */
    @Value("${prjxp.gldrtrvr.doc-max-chars:2000}")
    int docMaxChars = 2000;

    /** Half-window size (chars) around the match when windowing a doc chunk. */
    @Value("${prjxp.gldrtrvr.doc-window-chars:500}")
    int docWindowChars = 500;

    /** Max total chars for all doc chunks in one vectorSearch answer (doc-specific content budget,
     *  smaller than the code budget). Initialized with the property default so that non-Spring
     *  construction (tests) behaves like the configured default. */
    @Value("${prjxp.gldrtrvr.doc-max-content-length:8000}")
    int docMaxContentLength = 8000;

    @Override
    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).orElseThrow();
        List<ScoredChunk> textChunks = combineScoredChunksByID(chunkDao, chunks).stream()
                .filter(sc -> MIME.equals(sc.chunk().getMimeType()))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
        // Doc-specific content budget (Phase 03c): docs get a smaller budget than code
        int budget = Math.min(params.getMaxContentLength(), docMaxContentLength);
        int skipped = 0;
        for (var sc : textChunks) {
            String part = formatChunk(sc.chunk());
            if (prompt.length() + part.length() > budget) {
                skipped++;
                continue;
            }
            prompt.append(part);
        }
        if (skipped > 0) {
            prompt.append("\n[weitere ").append(skipped).append(" Textauszüge wegen Groessenlimit nicht enthalten]\n");
        }
        return prompt;
    }

    private String formatChunk(PxChunk chunk) {
        String freshness = freshnessNote(chunk);
        return "\n## Textauszug aus " + chunk.getFile() + (freshness == null ? "" : " " + freshness) + ":\n"
                // Doc-chunk windowing (Phase 03b): vectorSearch hits are semantic, so query=null —
                // the deterministic fallback (first docMaxChars chars + marker) applies.
                + DocWindower.windowDoc(chunk.getContent(), null, chunk.getFile(),
                        chunk.getMimeType(), docMaxChars, docWindowChars) + "\n";
    }

    /** A "(Index-Stand ...)" freshness note when the chunk carries a {@code generated_at} watermark; null otherwise. */
    private String freshnessNote(PxChunk chunk) {
        String ga = chunk.getMetadata().get("generated_at");
        if (ga == null || ga.isBlank()) { return null; }
        try {
            return "(Index-Stand " + java.time.Instant.ofEpochMilli(Long.parseLong(ga))
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ")";
        } catch (NumberFormatException e) { return null; }
    }

    @Override
    @SafeVarargs
    public final List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
        return Collections.emptyList();
    }

    /**
     * Groups the scored chunks by ID and recombines the parts of each file. If not all parts
     * of a file were retrieved, the missing parts are loaded from the store so that the whole
     * file is presented (mirrors {@code MarkdownRetriever}).
     */
    private List<ScoredChunk> combineScoredChunksByID(PxChunkDao chunkDao, List<ScoredChunk> chunks) {
        Map<String, List<ScoredChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            chunkMap.computeIfAbsent(c.chunk().getId(), k -> new ArrayList<>()).add(c);
        }
        List<ScoredChunk> result = new ArrayList<>();
        for (var list : chunkMap.values()) {
            ScoredChunk first = list.getFirst();
            double bestScore = list.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
            if (first.chunk().getTotal() > list.size()) {
                result.add(new ScoredChunk(PxChunk.combine(chunkDao.findById(first.chunk().getId())), bestScore));
            } else {
                result.add(new ScoredChunk(PxChunk.combine(new ArrayList<>(list.stream().map(ScoredChunk::chunk).toList())), bestScore));
            }
        }
        return result;
    }
}
