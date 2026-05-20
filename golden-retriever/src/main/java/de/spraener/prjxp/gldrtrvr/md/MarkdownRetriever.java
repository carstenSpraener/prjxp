package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log
public class MarkdownRetriever implements GoldenRetriever {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, StringBuilder prompt, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).orElseThrow();
        // Die Session verwaltet den Baum-Aufbau der Dokumente
        MarkdownPromptSession session = new MarkdownPromptSession(chunkDao, rankingService);
        session.setChunks(combineChunksByID(chunkDao, chunks));

        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    private String modifyPromptByChunk(PxChunk pxChunk, String currentPrompt) {
        String type = pxChunk.getMetadata().get("pxchunk_type");
        StringBuilder sb = new StringBuilder(currentPrompt);

        if( "FILE".equals(type) ) {
            sb.append("\nIn Datei: ").append(pxChunk.getFile()).append(":\n");
        } else if( "SECTION".equals(type) ) {
            sb.append( "\n## "+pxChunk.getContent()+"\n");
        } else {
            sb.append("\n").append(pxChunk.getContent());
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
}