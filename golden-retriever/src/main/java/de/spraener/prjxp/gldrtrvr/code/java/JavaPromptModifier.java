package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.enrichment.SearchParams;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

@Data
@RequiredArgsConstructor
@Log
public class JavaPromptModifier implements JavaPromptSession.PromptModifier  {
    private final SearchParams searchParams;

    @Override
    public String modifyPrompt(PxChunkDao chunkDao, PxChunk pxChunk, String prompt) {
        String nextPrompt = prompt;
        if (pxChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(pxChunk.getMetadata().get("java_code_section"));
            switch (section) {
                case METHOD:
                    if( searchParams!=null && !searchParams.isSkeletonsOnly() ) {
                        PxChunk javaDoc = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".javadoc"));
                        if (javaDoc != null) {
                            nextPrompt = insertBefore(prompt, toMethodName(pxChunk), javaDoc.getContent());
                            prompt = nextPrompt;
                        }
                        nextPrompt = replaceInPrompt(prompt, toMethodName(pxChunk), pxChunk.getContent());
                        break;
                    }
                    nextPrompt = replaceInPrompt(prompt, toMethodName(pxChunk), toMethodSignature(pxChunk.getContent()));
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
            log.fine("Methodenname %s nicht gefunden in Prompt: %s".formatted(methodName, prompt));
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
        String id = c.getId();
        if( id.contains("...") ) {
            // A wild hack to deal with varargs
            String head = id.substring(0, id.indexOf("..."));
            String tail = id.substring(id.lastIndexOf("..."));
            return head.substring(head.lastIndexOf('.') + 1) + tail;
        }
        return c.getId().substring(c.getId().lastIndexOf('.') + 1);
    }

    private String toMethodSignature(String methodContent) {
        if (methodContent == null || methodContent.isBlank()) {
            return methodContent;
        }
        for (String line : methodContent.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith("{")) {
                return line.replaceFirst("\\{\\s*$", ";");
            }
            return line;
        }
        return methodContent;
    }

}
