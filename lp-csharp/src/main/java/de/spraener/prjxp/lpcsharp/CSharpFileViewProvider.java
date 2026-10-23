package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;

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
 * Renders the complete source view of one C# file from its combined index units:
 * per-type frames with all member bodies (and doc comments) spliced in —
 * the "original" file structure, no relevance filtering, no budget.
 *
 * <p>Splicing matches each member's FIRST SOURCE LINE against the frame's member
 * lines: the {@code CSharpCodeChunker} emits exactly those first lines into the
 * typeFrame, so a trimmed line-equality match is exact — no normalized-signature
 * derivation needed (unlike the Java provider). Multi-line signatures and
 * attribute lines splice correctly because the whole member chunk replaces the
 * single frame line.
 *
 * <p>Plain, non-Spring object: it is created by {@link CSharpLanguagePack} inside
 * the host's isolating plugin class loader and handed to the host's
 * {@code FileViewRegistry} via {@link CSharpLanguagePack#fileViewProvider()}.
 */
public class CSharpFileViewProvider implements FileViewProvider {

    public static final String MIME = CSharpCodeChunker.CSHARP_CODE_MIME_TYPE;

    /** Member sections that get spliced into their owning type frame. */
    private static final List<CSharpCodeSection> MEMBER_SECTIONS = List.of(
            CSharpCodeSection.METHOD, CSharpCodeSection.CONSTRUCTOR, CSharpCodeSection.PROPERTY,
            CSharpCodeSection.FIELD, CSharpCodeSection.EVENT, CSharpCodeSection.INDEXER);

    /** Type declaration line template: any modifiers/attributes, then keyword + simple name (inserted quoted). */
    private static final String TYPE_DECL_TEMPLATE =
            ".*\\b(class|struct|interface|record|enum|delegate)\\s+$NAME\\b.*";

    /** Matches an enum declaration line (for the constants splice). */
    private static final Pattern ENUM_DECL = Pattern.compile(
            "\\s*(public\\s+|private\\s+|internal\\s+|protected\\s+|static\\s+|abstract\\s+|sealed\\s+)*enum\\s+\\w+.*");

    @Override
    public String mimeType() {
        return MIME;
    }

    @Override
    public String language() {
        return "csharp";
    }

    @Override
    public String render(List<PxChunk> units) {
        List<PxChunk> frames = sortByFromLine(units, CSharpCodeSection.TYPE_FRAME);
        List<PxChunk> members = new ArrayList<>();
        for (CSharpCodeSection section : MEMBER_SECTIONS) {
            members.addAll(sortByFromLine(units, section));
        }
        List<PxChunk> enumConstants = sortByFromLine(units, CSharpCodeSection.ENUM_CONSTANT);
        List<PxChunk> usings = sortByFromLine(units, CSharpCodeSection.USINGS);
        Map<String, PxChunk> docs = new HashMap<>();
        for (PxChunk unit : units) {
            String section = unit.getMetadata() == null ? null : unit.getMetadata().get(CSharpCodeSection.METADATA_KEY);
            if (CSharpCodeSection.TYPE_DOC.getName().equals(section)
                    || CSharpCodeSection.METHOD_DOC.getName().equals(section)) {
                docs.put(unit.getId(), unit);
            }
        }

        if (frames.isEmpty()) {
            // rawFile parts (parse failure) or a frameless file: concatenate in line order
            StringBuilder fallback = new StringBuilder();
            units.stream()
                    .filter(u -> u.getContent() != null && !u.getContent().isBlank())
                    .sorted(Comparator.comparingInt(u -> ReaderSupport.parseLine(u.getFromLine())))
                    .forEach(u -> {
                        if (fallback.length() > 0) {
                            fallback.append('\n');
                        }
                        fallback.append(u.getContent());
                    });
            return fallback.toString();
        }

        String usingsBlock = usings.isEmpty() || usings.get(0).getContent() == null
                ? "" : usings.get(0).getContent() + "\n";

        StringBuilder result = new StringBuilder();
        Set<String> placed = new HashSet<>();
        Set<String> frameIds = new HashSet<>();
        for (PxChunk frame : frames) {
            frameIds.add(frame.getId());
        }
        for (PxChunk frame : frames) {
            result.append(renderFrame(frame, usingsBlock, members, enumConstants, docs, placed, frameIds));
        }
        // orphan members (parent frame missing from the index)
        for (PxChunk member : members) {
            if (!placed.contains(member.getId())) {
                renderOrphan(result, member, docs.get(member.getId() + ".doc"));
            }
        }
        // orphan enum constants (parent frame missing from the index)
        for (PxChunk ec : enumConstants) {
            if (!placed.contains(ec.getId())) {
                renderOrphan(result, ec, null);
            }
        }
        return result.toString();
    }

    private static List<PxChunk> sortByFromLine(List<PxChunk> units, CSharpCodeSection section) {
        List<PxChunk> result = new ArrayList<>();
        for (PxChunk unit : units) {
            if (unit.getMetadata() != null && section.getName().equals(unit.getMetadata().get(CSharpCodeSection.METADATA_KEY))) {
                result.add(unit);
            }
        }
        result.sort(Comparator.comparingInt(u -> ReaderSupport.parseLine(u.getFromLine())));
        return result;
    }

    private static String renderFrame(PxChunk frame, String usingsBlock, List<PxChunk> members,
                                      List<PxChunk> enumConstants, Map<String, PxChunk> docs,
                                      Set<String> placed, Set<String> frameIds) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        // Prepend the usings to every frame (analog of the Java frame's package + imports block)
        if (!usingsBlock.isEmpty()) {
            lines.addAll(0, Arrays.asList(usingsBlock.split("\n", -1)));
        }

        Set<Integer> usedLines = new HashSet<>();
        List<SplicePlan> plans = new ArrayList<>();
        for (PxChunk member : members) {
            // The id-prefix fallback only applies to orphan members (parent frame missing from the
            // units) — otherwise a nested member (id "FQN.Inner.sig") would also be spliced into
            // every ancestor frame, since its id starts with "FQN.".
            boolean parentIsFrame = frameIds.contains(member.getParent());
            boolean ownedByFrame = frame.getId().equals(member.getParent())
                    || (!parentIsFrame
                        && member.getId().length() > frame.getId().length() + 1
                        && member.getId().startsWith(frame.getId() + "."));
            if (!ownedByFrame) {
                continue;
            }
            placed.add(member.getId());
            String firstLine = firstLineOf(member.getContent());
            plans.add(planFirstLine(lines, usedLines, firstLine, docs.get(member.getId() + ".doc"), member.getContent()));
        }
        applyInDescendingIndexOrder(lines, plans);

        // Enum constants: replace the frame's per-constant lines with the full constants block
        for (PxChunk ec : enumConstants) {
            if (!frame.getId().equals(ec.getParent())) {
                continue;
            }
            placed.add(ec.getId());
            spliceEnumConstants(lines, ec);
        }

        // Type doc: above the type declaration line (the frame carries no doc lines)
        PxChunk typeDoc = docs.get(frame.getId() + ".doc");
        if (typeDoc != null && typeDoc.getContent() != null && !typeDoc.getContent().isBlank()) {
            spliceTypeDoc(lines, simpleName(frame.getId()), typeDoc.getContent());
        }

        return "\n## " + frame.getId() + "\n\n```csharp\n" + String.join("\n", lines) + "\n```\n";
    }

    /**
     * Plans the splice of one member: find its first source line in the frame,
     * reserve the line (plus attribute lines directly above it), and build the
     * replacement (doc comment + full member content). When no target line is
     * found, the plan falls back to insertion before the last closing brace.
     */
    private static SplicePlan planFirstLine(List<String> lines, Set<Integer> usedLines, String firstLine,
                                            PxChunk doc, String bodyContent) {
        String target = firstLine.strip();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!usedLines.contains(i) && !target.isEmpty() && lines.get(i).strip().equals(target)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return new SplicePlan(-1, -1, replacementOf(doc, bodyContent));   // -> fallback insertion (see apply)
        }
        int top = idx;
        while (top > 0 && !usedLines.contains(top - 1) && lines.get(top - 1).strip().startsWith("[")) {
            top--;                                                              // attribute lines above the sig
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

    /**
     * Replaces the frame's per-constant lines (between the enum declaration and
     * its closing brace) with the full constants block. C# enum bodies contain
     * only constants, so everything in between is a constant line.
     */
    private static void spliceEnumConstants(List<String> lines, PxChunk ec) {
        List<String> constLines = Arrays.asList(ec.getContent().split("\n", -1));
        int declIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (ENUM_DECL.matcher(lines.get(i)).matches()) {
                declIdx = i;
                break;
            }
        }
        if (declIdx < 0) {
            int close = lastBrace(lines);
            lines.addAll(close >= 0 ? close : lines.size(), constLines);
            return;
        }
        int close = -1;
        for (int i = declIdx + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("}")) {
                close = i;
                break;
            }
        }
        if (close > declIdx) {
            lines.subList(declIdx + 1, close).clear();
        }
        lines.addAll(declIdx + 1, constLines);
    }

    /** Inserts the type doc above the type declaration line (and its attribute lines). */
    private static void spliceTypeDoc(List<String> lines, String simpleName, String docContent) {
        List<String> docLines = Arrays.asList(docContent.split("\n", -1));
        Pattern decl = Pattern.compile(TYPE_DECL_TEMPLATE.replace("$NAME", Pattern.quote(simpleName)));
        int declIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (decl.matcher(lines.get(i)).matches()) {
                declIdx = i;
                break;
            }
        }
        if (declIdx < 0) {
            // fallback: after the namespace opening line (or at the top)
            int ns = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith("namespace ")) {
                    ns = i;
                    break;
                }
            }
            lines.addAll(ns >= 0 ? ns + 1 : 0, docLines);
            return;
        }
        int top = declIdx;
        while (top > 0 && lines.get(top - 1).strip().startsWith("[")) {
            top--;
        }
        lines.addAll(top, docLines);
    }

    private static void renderOrphan(StringBuilder result, PxChunk unit, PxChunk doc) {
        result.append("## ").append(unit.getId()).append("\n\n```csharp\n");
        if (doc != null && doc.getContent() != null && !doc.getContent().isBlank()) {
            result.append(doc.getContent()).append("\n");
        }
        result.append(unit.getContent()).append("```\n");
    }

    private static String firstLineOf(String content) {
        if (content == null) {
            return "";
        }
        int nl = content.indexOf('\n');
        return nl < 0 ? content : content.substring(0, nl);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static int lastBrace(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).strip().equals("}")) {
                return i;
            }
        }
        return -1;
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

    /** Splice coordinates (indices into the frame's lines list) plus the replacement lines.
     *  topLine &lt;= sigLine; both -1 when no target was found. */
    private record SplicePlan(int topLine, int sigLine, List<String> replacement) {
    }
}
