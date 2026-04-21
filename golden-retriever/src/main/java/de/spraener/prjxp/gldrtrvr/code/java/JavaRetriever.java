package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
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
public class JavaRetriever {
    private final PxChunkDao chunkDao;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(StringBuilder prompt, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        JavaPromptSession session = new JavaPromptSession(chunkDao);
        session.setChunks(combineChunksByID(chunks));
        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    private String modifyPromptByChunk(PxChunk pxChunk, String prompt) {
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

    private List<PxChunk> combineChunksByID(List<PxChunk> chunks) {
        Map<String, List<PxChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            List<PxChunk> idList = chunkMap.computeIfAbsent(c.getId(), k -> new ArrayList<>());
            idList.add(c);
        }
        List<PxChunk> result = new ArrayList<>();
        for (var chunkList : chunkMap.values()) {
            PxChunk c = chunkList.getFirst();
            if (c.getTotal() > chunkList.size()) {
                result.add(combineChunks(chunkDao.findById(c.getId())));
            } else {
                result.add(combineChunks(chunkList));
            }
        }
        return result;
    }

    private PxChunk combineChunks(List<PxChunk> chunkList) {
        return PxChunk.combine(chunkList);
    }
}
