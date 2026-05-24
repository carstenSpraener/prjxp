package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
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
public class JavaRetriever implements GoldenRetriever {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final ChunkRankingService rankingService;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        StringBuilder prompt = new StringBuilder();
        PxChunkDao chunkDao = chunkDaoProvider.get(projectName).get();
        List<PxChunk> javaChunks = combineChunksByID(chunkDao, chunks);
        if( javaChunks.isEmpty() ) {
            return prompt;
        }
        JavaPromptSession session = new JavaPromptSession(chunkDao, rankingService);
        session.setChunks(javaChunks);
        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    private String modifyPromptByChunk(PxChunkDao chunkDao, PxChunk pxChunk, String prompt) {
        String nextPrompt = prompt;
        if (pxChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(pxChunk.getMetadata().get("java_code_section"));
            switch (section) {
                case METHOD:
                    PxChunk javaDoc = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".javadoc"));
                    if (javaDoc != null) {
                        nextPrompt = insertBefore(prompt, toMethodName(pxChunk), javaDoc.getContent());
                        prompt = nextPrompt;
                    }
                    nextPrompt = replaceInPrompt(prompt, toMethodName(pxChunk), pxChunk.getContent());
                    break;
                case DEPENDENCIE_INFO:
                    nextPrompt = prompt + pxChunk.getContent();
                    break;
                case METHOD_DOC:
                    nextPrompt = insertBefore(prompt, toMethodName(pxChunk), pxChunk.getContent());
                    break;
                case CLAZZ_FRAME:
                    String className = pxChunk.getId();
                    nextPrompt = prompt + "\n\n## Hier ein Rumpf der Klasse " + className + ":\n\n```java\n" + pxChunk.getContent() + "\n```\n";
                    PxChunk dependenyChunk = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".dependencies"));
                    if (dependenyChunk != null) {
                        nextPrompt += "\n\n### Hier noch Infos zu den Dependencies innerhalb des Projekts:\n\n" + dependenyChunk.getContent();
                    }
                    break;
                default:
                    break;
            }
        }
        return nextPrompt;
    }

    private String insertBefore(String prompt, String methodName, String content) {
        int splittIdx = prompt.indexOf(methodName);
        if (splittIdx < 0) {
            log.warning("Methodenname %s nicht gefunden in Prompt: %s".formatted(methodName, prompt));
            return prompt;
        }
        String prefix = prompt.substring(0, splittIdx);
        String postFix = prompt.substring(splittIdx);
        return prefix + content + postFix;
    }

    private String replaceInPrompt(String prompt, String methodName, String content) {
        return prompt.replace(methodName, content);
    }

    private String toMethodName(PxChunk c) {
        return c.getId().substring(c.getId().lastIndexOf('.') + 1);
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
