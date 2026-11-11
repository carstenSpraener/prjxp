package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.model.DocWindower;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.common.retrieval.SearchParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log
public class MarkdownRetriever implements GoldenRetriever {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;

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

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).orElseThrow();
        ContentLevel level = params.effectiveContentLevel();
        if (level == ContentLevel.POINTERS) {
            return buildPointersPrompt(chunkDao, chunks, params);
        }
        // Die Session verwaltet den Baum-Aufbau der Dokumente
        MarkdownPromptSession session = new MarkdownPromptSession(chunkDao, rankingService);
        // Doc-specific content budget (Phase 03c): docs get a smaller budget than code
        session.setMaxContentLength(Math.min(params.getMaxContentLength(), docMaxContentLength));
        session.setChunksByScore(combineScoredChunksByID(chunkDao, chunks));

        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    /**
     * POINTERS layer: no content, only a {@code readFile} pointer per doc file (de-duplicated).
     * Documentation is <b>not</b> in the code graph (the {@code parent} is a file name), so no
     * {@code graphNode} pointer is ever emitted; a chunk without a file contributes nothing.
     */
    private StringBuilder buildPointersPrompt(PxChunkDao chunkDao, List<ScoredChunk> chunks, SearchParams params) {
        StringBuilder prompt = new StringBuilder();
        List<ScoredChunk> mdChunks = combineScoredChunksByID(chunkDao, chunks).stream()
                .filter(sc -> "text/markdown".equals(sc.chunk().getMimeType()))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
        // Doc-specific content budget (Phase 03c): docs get a smaller budget than code
        int budget = Math.min(params.getMaxContentLength(), docMaxContentLength);
        Set<String> emittedFiles = new HashSet<>();
        int skipped = 0;
        for (var sc : mdChunks) {
            String part = pointerFor(sc.chunk());
            if (part.isEmpty()) {
                continue;   // no file: nothing to emit, not counted as skipped
            }
            if (!emittedFiles.add(sc.chunk().getFile())) {
                continue;   // this file was already pointed at
            }
            if (prompt.length() + part.length() > budget) {
                skipped++;
                continue;
            }
            prompt.append(part);
        }
        if (skipped > 0) {
            prompt.append("\n[weitere ").append(skipped).append(" Klassen wegen Groessenlimit nicht enthalten]\n");
        }
        return prompt;
    }

    /** POINTERS layer: a {@code readFile} pointer for the doc file; {@code ""} when the chunk has no file. */
    private String pointerFor(PxChunk chunk) {
        String file = chunk.getFile();
        if (file == null || file.isBlank()) {
            return "";
        }
        return "\n## " + file + "\n→ readFile(\"" + file + "\")\n";
    }

    @Override
    @SafeVarargs
    // TODO: Implement this method
    public final List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
        return Collections.EMPTY_LIST;
    }

    private String modifyPromptByChunk(PxChunk pxChunk, String currentPrompt) {
        String mimeType = pxChunk.getMimeType();
        if( !mimeType.equals("text/markdown")  ) {
            return "";
        }
        String type = pxChunk.getMetadata().get("pxchunk_type");
        StringBuilder sb = new StringBuilder(currentPrompt);

        if( "FILE".equals(type) ) {
            sb.append("\nIn Datei: ").append(pxChunk.getFile()).append(":\n");
        } else if( "SECTION".equals(type) ) {
            sb.append( "\n## "+pxChunk.getContent()+"\n");
        } else {
            // Doc-chunk windowing (Phase 03b): vectorSearch hits are semantic, so query=null —
            // the deterministic fallback (first docMaxChars chars + marker) applies.
            sb.append("\n").append(DocWindower.windowDoc(
                    pxChunk.getContent(), null, pxChunk.getFile(), mimeType, docMaxChars, docWindowChars));
        }
        return sb.toString();
    }

    private List<PxChunk> combineChunksByID(PxChunkDao chunkDao, List<PxChunk> chunks) {
        Map<String, List<PxChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            chunkMap.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(c);
        }

        List<PxChunk> result = new ArrayList<>();
        for (var list : chunkMap.values()) {
            PxChunk first = list.getFirst();
            // Wenn nicht alle Teile in den Findings waren, laden wir alle nach
            if (first.getTotal() > list.size()) {
                result.add(PxChunk.combine(chunkDao.findById(first.getId())));
            } else {
                result.add(PxChunk.combine(list));
            }
        }
        return result;
    }

    private List<ScoredChunk> combineScoredChunksByID(PxChunkDao chunkDao, List<ScoredChunk> chunks) {
        Map<String, List<ScoredChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            chunkMap.computeIfAbsent(c.chunk().getId(), k -> new ArrayList<>()).add(c);
        }

        List<ScoredChunk> result = new ArrayList<>();
        for (var list : chunkMap.values()) {
            ScoredChunk first = list.getFirst();
            double bestScore = list.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
            // Wenn nicht alle Teile in den Findings waren, laden wir alle nach
            if (first.chunk().getTotal() > list.size()) {
                result.add(new ScoredChunk(PxChunk.combine(chunkDao.findById(first.chunk().getId())), bestScore));
            } else {
                result.add(new ScoredChunk(PxChunk.combine(new ArrayList<>(list.stream().map(ScoredChunk::chunk).toList())), bestScore));
            }
        }
        return result;
    }
}