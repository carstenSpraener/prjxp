package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.enrichment.SearchParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log
public class JavaRetriever implements GoldenRetriever {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
        List<ScoredChunk> javaChunks = combineScoredChunksByID(chunkDao, chunks);
        if( javaChunks.isEmpty() ) {
            return prompt;
        }
        JavaPromptSession session = new JavaPromptSession(chunkDao, rankingService);
        session.setMaxContentLength(params.getMaxContentLength());
        session.setChunksByScore(javaChunks);
        prompt.append(session.buildPrompt(new JavaPromptModifier(params), contextValidators));
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
        return session.buildSearchHits(new JavaPromptModifier(null), contextValidators);
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
