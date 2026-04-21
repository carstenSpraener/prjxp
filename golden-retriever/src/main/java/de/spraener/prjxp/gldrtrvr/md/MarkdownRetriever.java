package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.GoldenRetriever;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log
public class MarkdownRetriever implements GoldenRetriever {
    private final PxChunkDao chunkDao;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(StringBuilder prompt, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        // Die Session verwaltet den Baum-Aufbau der Dokumente
        MarkdownPromptSession session = new MarkdownPromptSession(chunkDao);
        session.setChunks(combineChunksByID(chunks));

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

    private List<PxChunk> combineChunksByID(List<PxChunk> chunks) {
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