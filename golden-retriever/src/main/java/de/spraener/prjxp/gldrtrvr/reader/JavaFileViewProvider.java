package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the complete source view of one Java file from its combined index units:
 * per-class frame skeletons with all method bodies (and javadocs) spliced in —
 * the "original" file structure, no relevance filtering, no budget.
 */
@Component
public class JavaFileViewProvider implements FileViewProvider {

    public static final String MIME = "text/x-java-code";
    private static final String SECTION_KEY = "java_code_section";
    private static final String SECTION_CLASS_FRAME = "classFrame";
    private static final String SECTION_METHOD = "method";
    private static final String SECTION_METHOD_DOC = "methodDoc";

    /** Splice coordinates (indices into the ORIGINAL lines list) plus the replacement lines.
     *  topLine &lt;= sigLine; both -1 when no target was found. */
    private record SplicePlan(int topLine, int sigLine, List<String> replacement) {
    }

    @Override
    public String mimeType() {
        return MIME;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String render(List<PxChunk> units) {
        List<PxChunk> frames = sortByFromLine(units, SECTION_CLASS_FRAME);
        List<PxChunk> methods = sortByFromLine(units, SECTION_METHOD);
        Map<String, PxChunk> docs = new HashMap<>();
        for (PxChunk unit : units) {
            if (SECTION_METHOD_DOC.equals(unit.getMetadata().get(SECTION_KEY))) {
                docs.put(unit.getId(), unit);
            }
        }

        if (frames.isEmpty()) {
            StringBuilder fallback = new StringBuilder();
            units.stream()
                    .sorted(Comparator.comparingInt(u -> ReaderSupport.parseLine(u.getFromLine())))
                    .forEach(u -> {
                        if (fallback.length() > 0) {
                            fallback.append('\n');
                        }
                        fallback.append(u.getContent());
                    });
            return fallback.toString();
        }

        StringBuilder result = new StringBuilder();
        Set<String> placed = new HashSet<>();
        for (PxChunk frame : frames) {
            result.append(renderFrame(frame, methods, docs, placed));
        }
        // orphan methods (parent FQN has no frame unit, e.g. frame was dropped from the index):
        for (PxChunk method : methods) {
            if (!placed.contains(method.getId())) {
                PxChunk doc = docs.get(method.getId() + ".javadoc");
                result.append("## ").append(method.getId()).append("\n\n```java\n");
                if (doc != null && doc.getContent() != null) {
                    result.append(doc.getContent());
                }
                result.append(method.getContent()).append("```\n");
            }
        }
        return result.toString();
    }

    private static List<PxChunk> sortByFromLine(List<PxChunk> units, String section) {
        List<PxChunk> result = new ArrayList<>();
        for (PxChunk unit : units) {
            if (section.equals(unit.getMetadata().get(SECTION_KEY))) {
                result.add(unit);
            }
        }
        result.sort(Comparator.comparingInt(u -> ReaderSupport.parseLine(u.getFromLine())));
        return result;
    }

    private static String renderFrame(PxChunk frame, List<PxChunk> methods, Map<String, PxChunk> docs, Set<String> placed) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        Set<Integer> usedLines = new HashSet<>();
        List<SplicePlan> plans = new ArrayList<>();
        for (PxChunk method : methods) {
            boolean ownedByFrame = frame.getId().equals(method.getParent())
                    || (method.getId().length() > frame.getId().length() + 1
                        && method.getId().startsWith(frame.getId() + "."));  // parent first, id-prefix fallback
            if (!ownedByFrame) {
                continue;
            }
            placed.add(method.getId());
            String sig = method.getId().substring(frame.getId().length() + 1);   // e.g. "public MClass createMClass()"
            plans.add(planSig(lines, usedLines, sig, docs.get(method.getId() + ".javadoc"), method.getContent()));
        }
        applyInDescendingIndexOrder(lines, plans);
        return "\n## " + frame.getId() + "\n\n```java\n" + String.join("\n", lines) + "\n```\n";
    }

    private static SplicePlan planSig(List<String> lines, Set<Integer> usedLines, String sig, PxChunk doc, String bodyContent) {
        String sigNorm = sig.strip();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!usedLines.contains(i) && lines.get(i).strip().equals(sigNorm)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return new SplicePlan(-1, -1, replacementOf(doc, bodyContent));   // -> fallback insertion (see apply)
        }
        int top = idx;
        while (top > 0 && lines.get(top - 1).strip().startsWith("@")) {
            top--;                                                              // annotation lines above the sig
        }
        for (int i = top; i <= idx; i++) {
            usedLines.add(i);                                                   // reserve the whole block
        }
        return new SplicePlan(top, idx, replacementOf(doc, bodyContent));
    }

    private static void applyInDescendingIndexOrder(List<String> lines, List<SplicePlan> plans) {
        plans.sort(Comparator.comparingInt(SplicePlan::sigLine).reversed());
        for (SplicePlan plan : plans) {
            if (plan.sigLine() < 0) {
                // no target found: insert before the LAST "}" line (or append at end if none)
                int close = -1;
                for (int i = lines.size() - 1; i >= 0; i--) {
                    if (lines.get(i).strip().equals("}")) {
                        close = i;
                        break;
                    }
                }
                lines.addAll(close >= 0 ? close : lines.size(), plan.replacement());
            } else {
                lines.subList(plan.topLine(), plan.sigLine() + 1).clear();
                lines.addAll(plan.topLine(), plan.replacement());
            }
        }
    }

    private static List<String> replacementOf(PxChunk doc, String bodyContent) {
        List<String> replacement = new ArrayList<>();
        if (doc != null && doc.getContent() != null && !doc.getContent().isBlank()) {
            replacement.addAll(Arrays.asList(doc.getContent().split("\n", -1)));
        }
        if (bodyContent != null) {
            replacement.addAll(Arrays.asList(bodyContent.split("\n", -1)));
        }
        return replacement;
    }
}
