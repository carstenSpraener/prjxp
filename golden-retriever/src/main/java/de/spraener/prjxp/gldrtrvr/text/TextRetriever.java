package de.spraener.prjxp.gldrtrvr.text;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
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

    @Override
    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).orElseThrow();
        List<ScoredChunk> textChunks = combineScoredChunksByID(chunkDao, chunks).stream()
                .filter(sc -> MIME.equals(sc.chunk().getMimeType()))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
        int budget = params.getMaxContentLength();
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
        return "\n## Textauszug aus " + chunk.getFile() + ":\n" + chunk.getContent() + "\n";
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
