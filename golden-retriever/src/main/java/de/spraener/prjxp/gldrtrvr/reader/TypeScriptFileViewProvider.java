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
import java.util.regex.Pattern;

/**
 * Renders the complete source view of one TypeScript file from its combined index units:
 * per-class frame skeletons with all method bodies (and jsdocs) spliced in —
 * the "original" file structure, no relevance filtering, no budget.
 */
@Component
public class TypeScriptFileViewProvider implements FileViewProvider {

    public static final String MIME = "text/x-typescript-code";
    private static final String SECTION_KEY = "typescript_code_section";
    private static final String SECTION_CLASS_FRAME = "classFrame";
    private static final String SECTION_METHOD = "method";
    private static final String SECTION_METHOD_DOC = "methodDoc";

    /** Synthetic first line of methods that have no jsdoc: {@code \t// Method <name> in class <Class>:}. */
    private static final Pattern SYNTHETIC_PREFIX = Pattern.compile("^\\t// Method \\w+ in class \\w+:$");

    /** Splice coordinates (index into the ORIGINAL lines list) plus the replacement lines; -1 = no target found. */
    private record SplicePlan(int line, List<String> replacement) {
    }

    @Override
    public String mimeType() {
        return MIME;
    }

    @Override
    public String language() {
        return "typescript";
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
            return joinAllUnits(units);
        }

        StringBuilder result = new StringBuilder();
        Set<String> placed = new HashSet<>();
        for (PxChunk frame : frames) {
            result.append(renderFrame(frame, methods, docs, placed));
        }
        // orphan top-level functions (parent == module, no class frame in the index):
        for (PxChunk method : methods) {
            if (!placed.contains(method.getId())) {
                result.append("## ").append(method.getId()).append("\n\n```typescript\n")
                        .append(unitText(docs, method)).append("```\n");
            }
        }
        return result.toString();
    }

    private static String renderFrame(PxChunk frame, List<PxChunk> methods, Map<String, PxChunk> docs, Set<String> placed) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        Set<Integer> usedLines = new HashSet<>();
        List<SplicePlan> plans = new ArrayList<>();
        for (PxChunk method : methods) {
            boolean ownedByFrame = frame.getId().equals(method.getParent()) || idPrefixMatch(method, frame.getId());
            if (!ownedByFrame) {
                continue;
            }
            placed.add(method.getId());
            String body = stripPrefix(method.getContent());
            PxChunk doc = docs.get(method.getId() + ".jsdoc");
            String docText = doc != null ? stripPrefix(doc.getContent()) : null;
            String bodyFirst = firstLine(body);
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (!usedLines.contains(i) && normalizeForMatch(lines.get(i)).equals(normalizeForMatch(bodyFirst))) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                usedLines.add(idx);
            }
            List<String> replacement = new ArrayList<>();
            if (docText != null && !docText.isBlank()) {
                replacement.addAll(Arrays.asList(docText.split("\n", -1)));
            }
            if (body != null) {
                replacement.addAll(Arrays.asList(body.split("\n", -1)));
            }
            plans.add(new SplicePlan(idx, replacement));
        }
        applyInDescendingIndexOrder(lines, plans);
        return "\n## " + frame.getId() + "\n\n```typescript\n" + String.join("\n", lines) + "\n```\n";
    }

    /** Apply plans in DESCENDING index order so earlier indices stay valid (overload safety). */
    private static void applyInDescendingIndexOrder(List<String> lines, List<SplicePlan> plans) {
        plans.sort(Comparator.comparingInt(SplicePlan::line).reversed());
        for (SplicePlan plan : plans) {
            if (plan.line() < 0) {
                // no target found: insert before the LAST "}" line (or append at end if none)
                int close = lastBraceIndex(lines);
                lines.addAll(close >= 0 ? close : lines.size(), plan.replacement());
            } else {
                lines.subList(plan.line(), plan.line() + 1).clear();
                lines.addAll(plan.line(), plan.replacement());
            }
        }
    }

    /** "    public foo(bar: string): void;"  vs  "    public foo(bar: string): void {"  ->  equal. */
    private static String normalizeForMatch(String line) {
        String s = line.strip();
        if (s.endsWith(";") || s.endsWith("{")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.strip().replaceAll("\\s+", " ");
    }

    /** Drops the synthetic {@code \t// Method ...} first line, if present. */
    private static String stripPrefix(String content) {
        if (content == null) {
            return content;
        }
        String[] l = content.split("\n", -1);
        if (l.length > 1 && SYNTHETIC_PREFIX.matcher(l[0]).matches()) {
            return String.join("\n", Arrays.copyOfRange(l, 1, l.length));
        }
        return content;
    }

    private static boolean idPrefixMatch(PxChunk method, String frameId) {
        return method.getId().length() > frameId.length() + 1 && method.getId().startsWith(frameId + ".");
    }

    /** Index of the last line whose stripped form ends with "}" (or -1). */
    private static int lastBraceIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).strip().endsWith("}")) {
                return i;
            }
        }
        return -1;
    }

    /** jsdoc + body, each prefix-stripped; used for orphan top-level functions. */
    private static String unitText(Map<String, PxChunk> docs, PxChunk method) {
        StringBuilder text = new StringBuilder();
        PxChunk doc = docs.get(method.getId() + ".jsdoc");
        if (doc != null) {
            text.append(stripPrefix(doc.getContent()));
        }
        text.append(stripPrefix(method.getContent()));
        return text.toString();
    }

    private static String firstLine(String content) {
        return content.split("\n", -1)[0];
    }

    /** Degraded fallback: all unit contents in fromLine order, joined by newline (same as the Java reader). */
    private static String joinAllUnits(List<PxChunk> units) {
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
}
