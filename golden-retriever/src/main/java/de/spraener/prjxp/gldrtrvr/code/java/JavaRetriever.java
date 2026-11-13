package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.PointerRenderer;
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
public class JavaRetriever implements GoldenRetriever {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;
    private final PointerRenderer pointerRenderer = new PointerRenderer();
    @Value("${prjxp.gldrtrvr.max-dependency-entries:5}")
    private int maxDependencyEntries = 5;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
        List<ScoredChunk> javaChunks = combineScoredChunksByID(chunkDao, chunks);
        if( javaChunks.isEmpty() ) {
            return prompt;
        }
        if (params.effectiveContentLevel() == ContentLevel.POINTERS) {
            return buildPointersPrompt(projectName, javaChunks, params);
        }
        JavaPromptSession session = new JavaPromptSession(chunkDao, rankingService);
        session.setMaxContentLength(params.getMaxContentLength());
        session.setChunksByScore(javaChunks);
        prompt.append(session.buildPrompt(new JavaPromptModifier(params, maxDependencyEntries), contextValidators));
        return prompt;
    }

    @SafeVarargs
    public final List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> scoredChunks, Function<String, Boolean>... contextValidators) {
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
        List<PxChunk> chunks = scoredChunks.stream().map(sc -> sc.chunk()).toList();
        List<ScoredChunk> javaChunks = combineScoredChunksByID(chunkDao, scoredChunks);
        if( javaChunks.isEmpty() ) {
            return Collections.EMPTY_LIST;
        }
        JavaPromptSession session = new JavaPromptSession(chunkDao, rankingService);
        session.setChunksByScore(javaChunks);
        return session.buildSearchHits(new JavaPromptModifier(null, maxDependencyEntries), contextValidators);
    }

    /**
     * POINTERS layer: one dual-graph reference block per hit, without the session tree.
     * A dependency chunk (no file, no {@code graph_node}) points at the type node it belongs to;
     * repeated dependency chunks of the same type are emitted once.
     */
    private StringBuilder buildPointersPrompt(String projectName, List<ScoredChunk> chunks, SearchParams params) {
        StringBuilder prompt = new StringBuilder();
        int budget = params.getMaxContentLength();
        Set<String> emittedDependencyTargets = new HashSet<>();
        int skipped = 0;
        for (var sc : chunks) {
            String dependencyTarget = dependencyTarget(sc.chunk());
            if (dependencyTarget != null && !emittedDependencyTargets.add(dependencyTarget)) {
                continue;   // this type's dependency pointer was already emitted
            }
            String block = pointerRenderer.render(projectName, sc.chunk(), sc.score(), dependencyTarget);
            if (prompt.length() + block.length() > budget) {
                skipped++;
                continue;
            }
            prompt.append(block);
        }
        if (skipped > 0) {
            prompt.append("\n[weitere ").append(skipped).append(" Klassen wegen Groessenlimit nicht enthalten]\n");
        }
        return prompt;
    }

    /** The parent type of a file-less dependency chunk, otherwise {@code null}. */
    private String dependencyTarget(PxChunk chunk) {
        if (chunk.getFile() != null && !chunk.getFile().isBlank()) {
            return null;
        }
        String parent = chunk.getParent();
        return parent == null || parent.isBlank() ? null : parent;
    }

    private List<ScoredChunk> combineScoredChunksByID(PxChunkDao chunkDao, List<ScoredChunk> chunks) {
        Map<String, List<ScoredChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            if (isJavaChunk(c.chunk())) {
                List<ScoredChunk> idList = chunkMap.computeIfAbsent(c.chunk().getId(), k -> new ArrayList<>());
                idList.add(c);
            }
        }
        List<ScoredChunk> result = new ArrayList<>();
        for (var chunkList : chunkMap.values()) {
            ScoredChunk first = chunkList.getFirst();
            PxChunk c = first.chunk();
            double bestScore = chunkList.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
            if (c.getTotal() > chunkList.size()) {
                PxChunk combinedChunk = combineChunks(chunkDao.findById(c.getId()));
                if (combinedChunk != null) {
                    result.add(new ScoredChunk(combinedChunk, bestScore));
                } else {
                    log.warning("The chunk [id='" + c.getId() + "'] to combine does not exist in the embedding store. Check your configuration.");
                }
            } else {
                PxChunk combinedChunk = combineChunks(new ArrayList<>(chunkList.stream().map(ScoredChunk::chunk).toList()));
                result.add(new ScoredChunk(combinedChunk, bestScore));
            }
        }
        return result;
    }

    private List<PxChunk> combineChunksByID(PxChunkDao chunkDao, List<PxChunk> chunks) {
        Map<String, List<PxChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            if( isJavaChunk(c) ) {
                List<PxChunk> idList = chunkMap.computeIfAbsent(c.getId(), k -> new ArrayList<>());
                idList.add(c);
            }
        }
        List<PxChunk> result = new ArrayList<>();
        for (var chunkList : chunkMap.values()) {
            PxChunk c = chunkList.getFirst();
            if (c.getTotal() > chunkList.size()) {
                PxChunk combinedChunk = combineChunks(chunkDao.findById(c.getId()));
                if( combinedChunk != null ) {
                    result.add(combinedChunk);
                } else {
                    log.warning("The chunk [id='"+c.getId()+"'] to combine does not exist in the embedding store. Check your configuration.");
                }
            } else {
                result.add(combineChunks(chunkList));
            }
        }
        return result;
    }

    private boolean isJavaChunk(PxChunk c) {
        return c!=null && c.getMetadata().containsKey("java_code_section");
    }

    private PxChunk combineChunks(List<PxChunk> chunkList) {
        return PxChunk.combine(chunkList);
    }
}
