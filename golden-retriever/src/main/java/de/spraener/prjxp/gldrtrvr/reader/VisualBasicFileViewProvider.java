package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the complete source view of one Visual Basic file from its combined index units:
 * per-container frame skeletons with all member bodies spliced in —
 * the "original" file structure, no relevance filtering, no budget.
 * Doc comment lines are never spliced: they remain verbatim inside the frame.
 */
@Component
public class VisualBasicFileViewProvider implements FileViewProvider {

    public static final String MIME = "text/x-visual-basic-code";
    private static final String SECTION_KEY = "visualbasic_code_section";
    private static final String SECTION_CLASS_FRAME = "classFrame";
    private static final String SECTION_METHOD = "method";

    private static final Pattern END_LINE =
            Pattern.compile("^(?i)\\s*End\\s+(Sub|Function|Property|Class|Module|Interface|Structure)\\b");

    /** Splice coordinates (index into the ORIGINAL lines list) plus the replacement lines; -1 = no target found. */
    private record SplicePlan(int line, List<String> replacement) {
    }

    @Override
    public String mimeType() {
        return MIME;
    }

    @Override
    public String language() {
        return "visualbasic";
    }

    @Override
    public String render(List<PxChunk> units) {
        List<PxChunk> frames = sortByFromLine(units, SECTION_CLASS_FRAME);
        List<PxChunk> methods = sortByFromLine(units, SECTION_METHOD);

        if (frames.isEmpty()) {
            return joinAllUnits(units);
        }

        StringBuilder result = new StringBuilder();
        Set<String> placed = new HashSet<>();
        for (PxChunk frame : frames) {
            result.append(renderFrame(frame, methods, placed));
        }
        // orphan methods (parent has no frame unit, e.g. frame was dropped from the index):
        for (PxChunk method : methods) {
            if (!placed.contains(method.getId())) {
                result.append("## ").append(method.getId()).append("\n\n```vb\n")
                        .append(method.getContent()).append("```\n");
            }
        }
        return result.toString();
    }

    private static String renderFrame(PxChunk frame, List<PxChunk> methods, Set<String> placed) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        Set<Integer> usedLines = new HashSet<>();
        List<SplicePlan> plans = new ArrayList<>();
        // NOTE: methodDoc units are intentionally NOT spliced — comment lines remain verbatim in the frame.
        for (PxChunk method : methods) {
            boolean ownedByFrame = frame.getId().equals(method.getParent()) || idPrefixMatch(method, frame.getId());
            if (!ownedByFrame) {
                continue;
            }
            placed.add(method.getId());
            String body = method.getContent();   // NO prefix stripping — VB bodies are pure source
            String bodyFirst = firstLine(body);
            int idx = -1;                        // frame keeps the declaration line VERBATIM -> exact match
            for (int i = 0; i < lines.size(); i++) {
                if (!usedLines.contains(i) && lines.get(i).strip().equals(bodyFirst.strip())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                usedLines.add(idx);
            }
            plans.add(new SplicePlan(idx, Arrays.asList(body.split("\n", -1))));
        }
        applyInDescendingIndexOrder(lines, plans);
        return "\n## " + frame.getId() + "\n\n```vb\n" + String.join("\n", lines) + "\n```\n";
    }

    /** Apply plans in DESCENDING index order so earlier indices stay valid (overload safety). */
    private static void applyInDescendingIndexOrder(List<String> lines, List<SplicePlan> plans) {
        plans.sort(Comparator.comparingInt(SplicePlan::line).reversed());
        for (SplicePlan plan : plans) {
            if (plan.line() < 0) {
                // no target found: insert before the LAST "End ..." line (or append at end if none)
                int end = lastEndIndex(lines);
                lines.addAll(end >= 0 ? end : lines.size(), plan.replacement());
            } else {
                lines.subList(plan.line(), plan.line() + 1).clear();
                lines.addAll(plan.line(), plan.replacement());
            }
        }
    }

    /** Index of the last line matching {@code ^\s*End (Sub|Function|Property|Class|Module|Interface|Structure)} (or -1). */
    private static int lastEndIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (END_LINE.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean idPrefixMatch(PxChunk method, String frameId) {
        return method.getId().length() > frameId.length() + 1 && method.getId().startsWith(frameId + ".");
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
