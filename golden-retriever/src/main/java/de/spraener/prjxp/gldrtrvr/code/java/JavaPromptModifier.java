package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.DependencyCompactor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

@Data
@RequiredArgsConstructor
@Log
public class JavaPromptModifier implements JavaPromptSession.PromptModifier  {
    private final SearchParams searchParams;
    private final int maxDependencyEntries;

    public JavaPromptModifier(SearchParams searchParams) {
        this(searchParams, 10);
    }

    @Override
    public String modifyPrompt(PxChunkDao chunkDao, PxChunk pxChunk, String prompt) {
        String nextPrompt = prompt;
        if (pxChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(pxChunk.getMetadata().get("java_code_section"));
            ContentLevel level = searchParams != null ? searchParams.effectiveContentLevel() : ContentLevel.FULL;
            switch (section) {
                case METHOD:
                    boolean reduced = level == ContentLevel.SKELETON || level == ContentLevel.POINTERS;
                    if( !reduced ) {
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
                    String clusterId = pxChunk.getMetadata().get(GraphMetadata.LEIDEN_CLUSTER_ID);
                    if (level != ContentLevel.POINTERS) {
                        // existing behaviour, unchanged
                        nextPrompt = prompt + "\n\n## Hier ein Rumpf der Klasse " + className + ":\n\n```java\n"
                                + pxChunk.getContent() + "\n```\n";
                        PxChunk dependenyChunk = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".dependencies"));
                        if (dependenyChunk != null) {
                            nextPrompt += "\n\n### Hier noch Infos zu den Dependencies innerhalb des Projekts:\n\n"
                                    + DependencyCompactor.compact(dependenyChunk.getContent(), maxDependencyEntries);
                        }
                    } else {
                        nextPrompt = prompt + "\n\n## " + className;
                    }
                    // pointer layer — emitted for EVERY content level
                    if (!nextPrompt.endsWith("\n")) {
                        nextPrompt += "\n";
                    }
                    if (clusterId != null && !clusterId.isBlank()) {
                        nextPrompt += "[cluster:" + clusterId + "]\n";
                    }
                    String file = pxChunk.getFile();
                    if (file != null && !file.isBlank()) {
                        nextPrompt += "→ readFile(\"" + file + "\")\n";
                    }
                    nextPrompt += "→ readBySignature(\"" + className + "\")\n";
                    nextPrompt += "→ graphNode(\"" + className + "\")\n";
                    break;
                default:
                    break;
            }
        }
        String ga = pxChunk.getMetadata().get("generated_at");
        if (ga != null && !ga.isBlank()) {
            try {
                nextPrompt += "\n\n[Index-Stand " + java.time.Instant.ofEpochMilli(Long.parseLong(ga))
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "]";
            } catch (NumberFormatException ignored) { }
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
