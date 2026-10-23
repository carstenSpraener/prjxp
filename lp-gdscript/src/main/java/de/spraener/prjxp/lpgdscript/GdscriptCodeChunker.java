package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.model.SymbolMetadata;
import de.spraener.prjxp.common.util.ContentSplitter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Line/indentation-based chunker for GDScript (.gd) files. No parser/AST:
 * statements are detected by indentation, classified by their first line,
 * assembled into header/fields/func/funcDoc/misc sections, and inner
 * {@code class X:} blocks are expanded recursively.
 */
public class GdscriptCodeChunker implements PxChunker {

    public static final String GDSCRIPT_CODE_MIME_TYPE = "text/x-gdscript-code";

    private final ContentSplitter splitter;

    public GdscriptCodeChunker() {
        this(1000, 100);
    }

    public GdscriptCodeChunker(int chunkSize, int overlap) {
        this.splitter = new ContentSplitter(chunkSize, overlap);
    }

    @Override
    public boolean matches(File f) {
        return f.getName().endsWith(".gd");
    }

    @Override
    public Stream<PxChunk> chunk(File f) {
        String text;
        try {
            text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return rawFallback(f, null);
        }
        String[] lines = toLines(text);

        String classValue = findTopLevelClassName(lines);
        String baseId;
        String className;
        if (classValue != null && !classValue.isEmpty()) {
            baseId = classValue;
            className = classValue;
        } else {
            baseId = f.getName();
            className = baseId.endsWith(".gd")
                    ? baseId.substring(0, baseId.length() - ".gd".length())
                    : baseId;
        }

        List<Section> sections = partition(lines, 0, baseId, className, f, null);
        if (sections.isEmpty()) {
            return rawFallback(f, text);
        }
        List<PxChunk> chunks = new ArrayList<>();
        for (Section s : sections) {
            chunks.addAll(emitSection(s, lines, f));
        }
        return chunks.stream();
    }

    // ------------------------------------------------------------------
    // Partitioning
    // ------------------------------------------------------------------

    private List<Section> partition(String[] lines, int baseIndent, String baseId,
                                    String className, File file, String parent) {
        List<Statement> statements = parseStatements(lines, baseIndent);
        List<Section> sections = new ArrayList<>();
        boolean preamble = true;

        for (int si = 0; si < statements.size(); si++) {
            Statement st = statements.get(si);
            Statement next = si + 1 < statements.size() ? statements.get(si + 1) : null;
            switch (st.kind) {
                case HEADER -> {
                    if (preamble) {
                        mergeOrAdd(sections, GdscriptCodeSection.HEADER, st.from, st.to,
                                baseId, parent, baseId, className);
                    } else {
                        addSection(sections, GdscriptCodeSection.MISC, st.from, st.to,
                                baseId + ".misc." + st.from, baseId, baseId, className);
                    }
                }
                case DOC -> {
                    if (next != null && next.kind == StmtKind.FUNC) {
                        String funcId = funcId(baseId, lines, next);
                        addSection(sections, GdscriptCodeSection.FUNC_DOC, st.from, st.to,
                                funcId + ".doc", funcId, baseId, className);
                        preamble = false;
                    } else if (preamble) {
                        mergeOrAdd(sections, GdscriptCodeSection.HEADER, st.from, st.to,
                                baseId, parent, baseId, className);
                    } else if (next != null && next.kind == StmtKind.FIELDS) {
                        mergeOrAdd(sections, GdscriptCodeSection.FIELDS, st.from, st.to,
                                baseId + ".fields", baseId, baseId, className);
                    } else {
                        addSection(sections, GdscriptCodeSection.MISC, st.from, st.to,
                                baseId + ".misc." + st.from, baseId, baseId, className);
                    }
                }
                case FIELDS -> {
                    preamble = false;
                    mergeOrAdd(sections, GdscriptCodeSection.FIELDS, st.from, st.to,
                            baseId + ".fields", baseId, baseId, className);
                }
                case FUNC -> {
                    preamble = false;
                    addSection(sections, GdscriptCodeSection.FUNC, st.from, st.to,
                            funcId(baseId, lines, st), baseId, baseId, className);
                }
                case INNER_CLASS -> {
                    preamble = false;
                    String innerName = parseClassName(lines[st.from]);
                    addSection(sections, GdscriptCodeSection.INNER_CLASS, st.from, st.to,
                            baseId + "." + innerName, baseId, baseId, className);
                }
                case MISC -> {
                    preamble = false;
                    mergeOrAdd(sections, GdscriptCodeSection.MISC, st.from, st.to,
                            baseId + ".misc." + st.from, baseId, baseId, className);
                }
            }
        }

        List<Section> result = new ArrayList<>();
        for (Section s : sections) {
            if (s.type == GdscriptCodeSection.INNER_CLASS) {
                result.addAll(expandInnerClass(s, lines, file));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    /** Walks the lines, grouping them into statements (start + block continuation). */
    private List<Statement> parseStatements(String[] lines, int baseIndent) {
        List<Statement> statements = new ArrayList<>();
        int i = 0;
        int n = lines.length;
        while (i < n) {
            if (isTopLevelStart(lines[i], baseIndent)) {
                int from = i;
                boolean inEnum = isEnumStart(lines[from]);
                int braceDepth = inEnum ? braceDelta(lines[from]) : 0;
                i++;
                while (i < n) {
                    String l = lines[i];
                    boolean unbalanced = inEnum && braceDepth > 0;
                    if (!isBlockContinuation(l, baseIndent) && !unbalanced) {
                        break;
                    }
                    braceDepth += braceDelta(l);
                    i++;
                }
                statements.add(new Statement(classify(lines[from]), from, i));
            } else {
                // A run of lines that belong to no statement (leading blanks / strays):
                // keep them as MISC so the tiling stays lossless.
                int from = i;
                i++;
                while (i < n && !isTopLevelStart(lines[i], baseIndent)) {
                    i++;
                }
                statements.add(new Statement(StmtKind.MISC, from, i));
            }
        }
        return statements;
    }

    /** Expands a {@code class X:} block into its own header/fields/func sections. */
    private List<Section> expandInnerClass(Section s, String[] lines, File file) {
        int from = s.from0;
        int to = s.to0;
        String innerName = parseClassName(lines[from]);
        String innerBaseId = s.baseId + "." + innerName;

        String[] body = Arrays.copyOfRange(lines, from + 1, to);
        int bodyBase = minIndent(body);
        List<Section> inner = partition(body, bodyBase, innerBaseId, innerName, file, s.baseId);

        for (Section is : inner) {
            is.from0 += from + 1;
            is.to0 += from + 1;
        }

        Section header = null;
        for (Section is : inner) {
            if (is.type == GdscriptCodeSection.HEADER) {
                header = is;
                break;
            }
        }
        if (header != null) {
            header.from0 = from; // the `class X:` line becomes the first header line
        } else {
            inner.add(0, new Section(GdscriptCodeSection.HEADER, from, from + 1,
                    innerBaseId, s.baseId, innerBaseId, innerName));
        }
        return inner;
    }

    // ------------------------------------------------------------------
    // Chunk emission
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private List<PxChunk> emitSection(Section s, String[] lines, File f) {
        String content = joinLines(lines, s.from0, s.to0);
        GdscriptCodeSection type = s.type;
        String baseId = s.baseId;
        String className = s.className;

        Supplier<PxChunk> supplier = () -> PxChunk.create(c -> {
            c.setId(s.id);
            c.setParent(s.parent);
            c.setMimeType(GDSCRIPT_CODE_MIME_TYPE);
            c.setFile(f.getAbsolutePath());
            c.setEmbeddingPrefix(className);
            c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, type.getName());
            switch (type) {
                case HEADER -> SymbolMetadata.applyClass(c.getMetadata(), "header", baseId, className);
                case FIELDS -> SymbolMetadata.applyClass(c.getMetadata(), "fields", baseId, className);
                case FUNC -> SymbolMetadata.applyMethod(c.getMetadata(), "func", baseId,
                        parseFuncName(lines[s.from0]), parseFuncDeclaration(lines, s.from0, s.to0));
                case FUNC_DOC -> SymbolMetadata.applyMethod(c.getMetadata(), "funcDoc", baseId,
                        funcNameFromId(s.parent), lines[s.from0].trim());
                default -> { /* MISC / RAW_FILE: no symbol metadata */ }
            }
        });
        return splitter.splitContent(content, s.from0, s.to0, supplier);
    }

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private Stream<PxChunk> rawFallback(File f, String text) {
        String abs = f.getAbsolutePath();
        if (text == null) {
            return Stream.of(PxChunk.create(c -> {
                c.setId(abs);
                c.setMimeType(GDSCRIPT_CODE_MIME_TYPE);
                c.setFile(abs);
                c.setContent("");
                c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, GdscriptCodeSection.RAW_FILE.getName());
            }));
        }
        String[] lines = toLines(text);
        String content = joinLines(lines, 0, lines.length);
        Supplier<PxChunk> supplier = () -> PxChunk.create(c -> {
            c.setId(abs);
            c.setMimeType(GDSCRIPT_CODE_MIME_TYPE);
            c.setFile(abs);
            c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, GdscriptCodeSection.RAW_FILE.getName());
        });
        return splitter.splitContent(content, 0, lines.length, supplier).stream();
    }

    // ------------------------------------------------------------------
    // Line / statement helpers
    // ------------------------------------------------------------------

    /**
     * Splits the text into lines (CRLF-safe). The trailing empty element produced by
     * {@code split(-1)} when the file ends with a newline is an artifact, not a real
     * line; it is dropped so that rejoining the chunk contents reproduces the file
     * byte-for-byte.
     */
    private static String[] toLines(String text) {
        String[] lines = text.split("\r?\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = Arrays.copyOf(lines, lines.length - 1);
        }
        return lines;
    }

    private static boolean isBlank(String l) {
        return l.trim().isEmpty();
    }

    private static int indent(String l) {
        if (isBlank(l)) {
            return 0;
        }
        int i = 0;
        while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static boolean isTopLevelStart(String l, int baseIndent) {
        return !isBlank(l) && indent(l) == baseIndent;
    }

    private static boolean isBlockContinuation(String l, int baseIndent) {
        return isBlank(l) || indent(l) > baseIndent;
    }

    private static int minIndent(String[] lines) {
        int min = 0;
        boolean found = false;
        for (String l : lines) {
            if (!isBlank(l)) {
                int ind = indent(l);
                if (!found || ind < min) {
                    min = ind;
                    found = true;
                }
            }
        }
        return found ? min : 0;
    }

    private static String findTopLevelClassName(String[] lines) {
        for (String l : lines) {
            if (indent(l) == 0) {
                String t = l.trim();
                if (t.startsWith("class_name")) {
                    return t.substring("class_name".length()).trim();
                }
            }
        }
        return null;
    }

    private static boolean isEnumStart(String l) {
        return l.trim().startsWith("enum");
    }

    private static int braceDelta(String l) {
        int d = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    private static StmtKind classify(String line) {
        String t = line.trim();
        if (t.startsWith("class_name")) {
            return StmtKind.HEADER;
        }
        if (t.startsWith("extends") || t.startsWith("@tool") || t.startsWith("@icon")) {
            return StmtKind.HEADER;
        }
        if (t.startsWith("func")) {
            return StmtKind.FUNC;
        }
        if (t.startsWith("class")) {
            return StmtKind.INNER_CLASS;
        }
        if (t.startsWith("var") || t.startsWith("const") || t.startsWith("enum")
                || t.startsWith("signal") || t.startsWith("@export") || t.startsWith("@onready")
                || t.startsWith("static") || t.startsWith("preload")) {
            return StmtKind.FIELDS;
        }
        if (t.startsWith("##")) {
            return StmtKind.DOC;
        }
        return StmtKind.MISC;
    }

    private static String parseClassName(String line) {
        String t = line.trim();
        if (t.startsWith("class")) {
            String rest = t.substring("class".length()).trim();
            if (rest.endsWith(":")) {
                rest = rest.substring(0, rest.length() - 1).trim();
            }
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                rest = rest.substring(0, sp).trim();
            }
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return "Anonymous";
    }

    private static String parseFuncName(String firstLine) {
        String t = firstLine.trim();
        if (t.startsWith("func")) {
            String rest = t.substring("func".length()).trim();
            int open = rest.indexOf('(');
            if (open > 0) {
                return rest.substring(0, open).trim();
            }
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                return rest.substring(0, sp).trim();
            }
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return "unknown";
    }

    private static String funcNameFromId(String funcId) {
        int dot = funcId.lastIndexOf('.');
        String rest = dot >= 0 ? funcId.substring(dot + 1) : funcId;
        int open = rest.indexOf('(');
        return open > 0 ? rest.substring(0, open) : rest;
    }

    private String funcId(String baseId, String[] lines, Statement st) {
        return baseId + "." + parseFuncName(lines[st.from]) + "(" + parseFuncParams(lines, st.from, st.to) + ")";
    }

    /** The signature text: from {@code func name(} up to and including the line with the closing {@code ):}. */
    private static String signatureText(String[] lines, int from, int to) {
        int depth = 0;
        int sigEnd = to;
        boolean foundOpen = false;
        for (int li = from; li < to; li++) {
            for (int ci = 0; ci < lines[li].length(); ci++) {
                char c = lines[li].charAt(ci);
                if (c == '(') {
                    depth++;
                    foundOpen = true;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (foundOpen && depth == 0) {
                sigEnd = li + 1;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int li = from; li < sigEnd; li++) {
            if (li > from) {
                sb.append('\n');
            }
            sb.append(lines[li]);
        }
        return sb.toString();
    }

    private static String parseFuncParams(String[] lines, int from, int to) {
        String sigText = signatureText(lines, from, to);
        int funcIdx = sigText.indexOf("func");
        if (funcIdx < 0) {
            return "";
        }
        int openIdx = sigText.indexOf('(', funcIdx);
        if (openIdx < 0) {
            return "";
        }
        int depth = 0;
        int closeIdx = -1;
        for (int i = openIdx; i < sigText.length(); i++) {
            char c = sigText.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    closeIdx = i;
                    break;
                }
            }
        }
        if (closeIdx < 0) {
            return "";
        }
        return sigText.substring(openIdx + 1, closeIdx).replaceAll("\\s+", " ").trim();
    }

    private static String parseFuncDeclaration(String[] lines, int from, int to) {
        return signatureText(lines, from, to).replaceAll("\\s+", " ").trim();
    }

    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(to, lines.length);
        for (int i = from; i < end; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static void addSection(List<Section> sections, GdscriptCodeSection type, int from, int to,
                                   String id, String parent, String baseId, String className) {
        sections.add(new Section(type, from, to, id, parent, baseId, className));
    }

    private static void mergeOrAdd(List<Section> sections, GdscriptCodeSection type, int from, int to,
                                   String id, String parent, String baseId, String className) {
        if (!sections.isEmpty()) {
            Section prev = sections.get(sections.size() - 1);
            if (prev.type == type && prev.to0 == from) {
                prev.to0 = to;
                return;
            }
        }
        addSection(sections, type, from, to, id, parent, baseId, className);
    }

    // ------------------------------------------------------------------
    // Internal models
    // ------------------------------------------------------------------

    private enum StmtKind {
        HEADER, FIELDS, FUNC, INNER_CLASS, DOC, MISC
    }

    private static final class Statement {
        final StmtKind kind;
        final int from;
        final int to;

        Statement(StmtKind kind, int from, int to) {
            this.kind = kind;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Section {
        GdscriptCodeSection type;
        int from0;
        int to0;
        String id;
        String parent;
        String baseId;
        String className;

        Section(GdscriptCodeSection type, int from0, int to0, String id, String parent,
                String baseId, String className) {
            this.type = type;
            this.from0 = from0;
            this.to0 = to0;
            this.id = id;
            this.parent = parent;
            this.baseId = baseId;
            this.className = className;
        }
    }
}
