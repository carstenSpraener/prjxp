package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.model.PxFileType;
import de.spraener.prjxp.common.model.SymbolMetadata;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.lpcsharp.grammar.CSharpLexer;
import de.spraener.prjxp.lpcsharp.grammar.CSharpParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.ParserRuleContext;

import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * C# chunker (C# grammar upgrade, Phase 02): walks the Roslyn-derived parse tree
 * (generated from {@code CSharp.g4}) and emits {@link PxChunk}s for types
 * (typeFrame), members (method / constructor / field / property / event /
 * indexer), enum constants, usings, and XML doc comments.
 *
 * <p>Chunk text is extracted by line range from the source (CRLF-safe), so
 * {@code #} preprocessor lines and doc comments flow into chunk text 1:1
 * wherever they lie inside a chunk's range. On any parse error or exception
 * the whole file is emitted as {@code rawFile} chunks instead (no semantics).
 *
 * <p>Not thread-safe: one file per {@link #chunk(File)} invocation.
 */
public class CSharpCodeChunker implements PxChunker {

    public static final String CSHARP_CODE_MIME_TYPE = "text/x-csharp-code";

    private static final Logger log = Logger.getLogger(CSharpCodeChunker.class.getName());

    private final ContentSplitter splitter;

    // Per-call state (reset at the start of every chunk() invocation).
    private String absPath;
    private String[] codeLines;
    /** Dot-joined namespace of the current scope ("" when none); save/restore around nested namespaces. */
    private String currentNamespace = "";
    /** Enclosing type names, outermost first (ArrayDeque iterates bottom → top). */
    private final Deque<String> typeNames = new ArrayDeque<>();
    /** Parse contexts of the enclosing types, parallel to {@link #typeNames}. */
    private final Deque<ParserRuleContext> typeContexts = new ArrayDeque<>();
    /** FQN of the first type in the file (anchor for the usings chunk). */
    private String firstTypeFqn;
    private String firstTypeName;

    public CSharpCodeChunker() {
        this(1000, 100);
    }

    public CSharpCodeChunker(int chunkSize, int overlap) {
        this.splitter = new ContentSplitter(chunkSize, overlap);
    }

    @Override
    public boolean matches(File f) {
        return PxFileType.C_SHARP_CODE.matches(f);
    }

    @Override
    public Stream<PxChunk> chunk(File f) {
        String text;
        try {
            text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("Cannot read " + f.getAbsolutePath() + ": " + e.getMessage());
            return Stream.empty();
        }
        absPath = f.getAbsolutePath();
        codeLines = text.split("\r?\n", -1);
        currentNamespace = "";
        typeNames.clear();
        typeContexts.clear();
        firstTypeFqn = null;
        firstTypeName = null;
        try {
            CSharpLexer lexer = new CSharpLexer(CharStreams.fromString(text));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            CSharpParser parser = new CSharpParser(tokens);
            parser.removeErrorListeners();
            ErrorCountingListener listener = new ErrorCountingListener();
            parser.addErrorListener(listener);
            CSharpParser.Compilation_unitContext tree = parser.compilation_unit();
            if (listener.errorCount > 0) {
                log.warning("Parse errors while chunking " + absPath + " (" + listener.errorCount
                        + "); falling back to raw file chunks.");
                return rawFallback(text);
            }
            return walkCompilationUnit(tree);
        } catch (Exception e) {
            log.warning("Exception while chunking " + absPath + ": " + e.getMessage());
            return rawFallback(text);
        }
    }

    // ------------------------------------------------------------------
    // Tree walk
    // ------------------------------------------------------------------

    private Stream<PxChunk> walkCompilationUnit(CSharpParser.Compilation_unitContext tree) {
        List<PxChunk> chunks = new ArrayList<>();
        for (CSharpParser.Member_declarationContext m : tree.member_declaration()) {
            walkMember(m, chunks);
        }
        List<CSharpParser.Using_directiveContext> usings = tree.using_directive();
        if (!usings.isEmpty()) {
            chunks.addAll(0, createUsingsChunks(usings));
        }
        return chunks.stream();
    }

    /**
     * Dispatches one member declaration. Used at the compilation-unit level,
     * inside namespaces, and inside type bodies (the dispatch is identical;
     * the FQN helpers adapt to the current scope).
     */
    private void walkMember(CSharpParser.Member_declarationContext m, List<PxChunk> chunks) {
        if (m.base_namespace_declaration() != null) {
            walkNamespace(m.base_namespace_declaration(), chunks);
        } else if (m.base_type_declaration() != null) {
            walkTypeLike(m.base_type_declaration(), chunks);
        } else if (m.delegate_declaration() != null) {
            handleDelegate(m.delegate_declaration(), chunks);
        } else if (m.base_field_declaration() != null) {
            walkFieldLike(m.base_field_declaration(), chunks);
        } else if (m.base_method_declaration() != null) {
            walkMethodLike(m.base_method_declaration(), chunks);
        } else if (m.base_property_declaration() != null) {
            walkPropertyLike(m.base_property_declaration(), chunks);
        }
        // global_statement / incomplete_member / enum_member_declaration:
        // recognized, no chunk (C# 10+ top-level statements, unfinished members).
    }

    private void walkNamespace(CSharpParser.Base_namespace_declarationContext ctx, List<PxChunk> chunks) {
        String name;
        List<CSharpParser.Member_declarationContext> members;
        if (ctx.namespace_declaration() != null) {
            CSharpParser.Namespace_declarationContext n = ctx.namespace_declaration();
            name = n.name().getText();
            members = n.member_declaration();
        } else {
            CSharpParser.File_scoped_namespace_declarationContext n = ctx.file_scoped_namespace_declaration();
            name = n.name().getText();
            members = n.member_declaration();
        }
        String saved = currentNamespace;
        currentNamespace = currentNamespace.isEmpty() ? name : currentNamespace + "." + name;
        for (CSharpParser.Member_declarationContext m : members) {
            walkMember(m, chunks);
        }
        currentNamespace = saved;
    }

    private void walkTypeLike(CSharpParser.Base_type_declarationContext bt, List<PxChunk> chunks) {
        if (bt.enum_declaration() != null) {
            handleEnum(bt.enum_declaration(), chunks);
        } else if (bt.type_declaration() != null) {
            handleTypeDeclaration(bt.type_declaration(), chunks);
        }
    }

    private void handleTypeDeclaration(CSharpParser.Type_declarationContext td, List<PxChunk> chunks) {
        if (td.class_declaration() != null) {
            CSharpParser.Class_declarationContext c = td.class_declaration();
            handleType(c, c.IDENTIFIER_TOKEN().getText(), c.member_declaration(), chunks);
        } else if (td.struct_declaration() != null) {
            CSharpParser.Struct_declarationContext c = td.struct_declaration();
            handleType(c, c.IDENTIFIER_TOKEN().getText(), c.member_declaration(), chunks);
        } else if (td.interface_declaration() != null) {
            CSharpParser.Interface_declarationContext c = td.interface_declaration();
            handleType(c, c.IDENTIFIER_TOKEN().getText(), c.member_declaration(), chunks);
        } else if (td.record_declaration() != null) {
            CSharpParser.Record_declarationContext c = td.record_declaration();
            handleType(c, c.IDENTIFIER_TOKEN().getText(), c.member_declaration(), chunks);
        } else if (td.union_declaration() != null) {
            CSharpParser.Union_declarationContext c = td.union_declaration();
            handleType(c, c.IDENTIFIER_TOKEN().getText(), c.member_declaration(), chunks);
        }
        // extension_block_declaration (C# 13): recognized, no chunk — it extends
        // another type rather than declaring one.
    }

    /**
     * Emits the typeFrame (and typeDoc) for one type and walks its members.
     * The type name/context are pushed onto the scope stacks for the duration.
     */
    private void handleType(ParserRuleContext ctx, String name,
                            List<CSharpParser.Member_declarationContext> members, List<PxChunk> chunks) {
        String parentFqn = typeNames.isEmpty() ? null : fqn();
        typeNames.addLast(name);
        typeContexts.addLast(ctx);
        String fqn = fqn();
        if (firstTypeFqn == null) {
            firstTypeFqn = fqn;
            firstTypeName = name;
        }
        emitTypeFrame(ctx, fqn, name, parentFqn, members, chunks);
        emitDocChunk(ctx, fqn, CSharpCodeSection.TYPE_DOC,
                meta -> SymbolMetadata.applyClass(meta, CSharpCodeSection.TYPE_DOC.getName(), fqn, name), chunks);
        for (CSharpParser.Member_declarationContext m : members) {
            walkMember(m, chunks);
        }
        typeNames.removeLast();
        typeContexts.removeLast();
    }

    /** Delegates are treated as types (v7 semantics): typeFrame + typeDoc, no members. */
    private void handleDelegate(CSharpParser.Delegate_declarationContext ctx, List<PxChunk> chunks) {
        String name = ctx.IDENTIFIER_TOKEN().getText();
        String parentFqn = typeNames.isEmpty() ? null : fqn();
        typeNames.addLast(name);
        typeContexts.addLast(ctx);
        String fqn = fqn();
        if (firstTypeFqn == null) {
            firstTypeFqn = fqn;
            firstTypeName = name;
        }
        emitTypeFrame(ctx, fqn, name, parentFqn, List.of(), chunks);
        emitDocChunk(ctx, fqn, CSharpCodeSection.TYPE_DOC,
                meta -> SymbolMetadata.applyClass(meta, CSharpCodeSection.TYPE_DOC.getName(), fqn, name), chunks);
        typeNames.removeLast();
        typeContexts.removeLast();
    }

    private void handleEnum(CSharpParser.Enum_declarationContext ctx, List<PxChunk> chunks) {
        String name = ctx.IDENTIFIER_TOKEN().getText();
        String parentFqn = typeNames.isEmpty() ? null : fqn();
        typeNames.addLast(name);
        typeContexts.addLast(ctx);
        String fqn = fqn();
        if (firstTypeFqn == null) {
            firstTypeFqn = fqn;
            firstTypeName = name;
        }
        List<CSharpParser.Enum_member_declarationContext> constants = ctx.enum_member_declaration();
        emitTypeFrame(ctx, fqn, name, parentFqn, constants, chunks);
        emitDocChunk(ctx, fqn, CSharpCodeSection.TYPE_DOC,
                meta -> SymbolMetadata.applyClass(meta, CSharpCodeSection.TYPE_DOC.getName(), fqn, name), chunks);
        if (!constants.isEmpty()) {
            int from0 = constants.get(0).getStart().getLine() - 1;
            int to0 = constants.get(constants.size() - 1).getStop().getLine();
            String content = lines(from0, to0);
            chunks.addAll(splitter.splitContent(content, from0, to0, () -> PxChunk.create(
                    c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                    c -> c.setId(fqn + ".constants"),
                    c -> c.setParent(fqn),
                    c -> c.setFile(absPath),
                    c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.ENUM_CONSTANT.getName()),
                    c -> SymbolMetadata.applyClass(c.getMetadata(), CSharpCodeSection.ENUM_CONSTANT.getName(), fqn, name)
            )));
        }
        typeNames.removeLast();
        typeContexts.removeLast();
    }

    private void walkFieldLike(CSharpParser.Base_field_declarationContext ctx, List<PxChunk> chunks) {
        if (ctx.event_field_declaration() != null) {
            CSharpParser.Event_field_declarationContext e = ctx.event_field_declaration();
            String name = firstDeclaratorName(e.variable_declaration());
            if (name != null) {
                emitMember(e, CSharpCodeSection.EVENT, name, name, collapsed(e), chunks);
            }
        } else if (ctx.field_declaration() != null) {
            CSharpParser.Field_declarationContext fd = ctx.field_declaration();
            String name = firstDeclaratorName(fd.variable_declaration());
            if (name != null) {
                emitMember(fd, CSharpCodeSection.FIELD, name, name, collapsed(fd), chunks);
            }
        }
    }

    private void walkMethodLike(CSharpParser.Base_method_declarationContext ctx, List<PxChunk> chunks) {
        if (ctx.method_declaration() != null) {
            CSharpParser.Method_declarationContext m = ctx.method_declaration();
            TerminalNode nameNode = m.IDENTIFIER_TOKEN();
            if (nameNode == null) {
                return;
            }
            String name = nameNode.getText();
            String idSuffix = name + "(" + paramListText(m.parameter_list()) + ")";
            emitMember(m, CSharpCodeSection.METHOD, idSuffix, name, collapsed(m), chunks);
        } else if (ctx.constructor_declaration() != null) {
            CSharpParser.Constructor_declarationContext c = ctx.constructor_declaration();
            TerminalNode nameNode = c.IDENTIFIER_TOKEN();
            if (nameNode == null) {
                return;
            }
            String name = nameNode.getText();
            String idSuffix = name + "(" + paramListText(c.parameter_list()) + ")";
            emitMember(c, CSharpCodeSection.CONSTRUCTOR, idSuffix, name, collapsed(c), chunks);
        }
        // conversion_operator_declaration / destructor_declaration /
        // operator_declaration: recognized, no chunk (no matching section).
    }

    private void walkPropertyLike(CSharpParser.Base_property_declarationContext ctx, List<PxChunk> chunks) {
        if (ctx.property_declaration() != null) {
            CSharpParser.Property_declarationContext p = ctx.property_declaration();
            TerminalNode nameNode = p.IDENTIFIER_TOKEN();
            if (nameNode == null) {
                return;
            }
            String name = nameNode.getText();
            emitMember(p, CSharpCodeSection.PROPERTY, name, name, collapsed(p), chunks);
        } else if (ctx.indexer_declaration() != null) {
            CSharpParser.Indexer_declarationContext ix = ctx.indexer_declaration();
            String idSuffix = "this(" + paramListText(ix.bracketed_parameter_list()) + ")";
            emitMember(ix, CSharpCodeSection.INDEXER, idSuffix, "this", collapsed(ix), chunks);
        } else if (ctx.event_declaration() != null) {
            CSharpParser.Event_declarationContext e = ctx.event_declaration();
            TerminalNode nameNode = e.IDENTIFIER_TOKEN();
            if (nameNode == null) {
                return;
            }
            String name = nameNode.getText();
            emitMember(e, CSharpCodeSection.EVENT, name, name, collapsed(e), chunks);
        }
    }

    // ------------------------------------------------------------------
    // Chunk emission
    // ------------------------------------------------------------------

    /**
     * Emits the typeFrame chunk: a synthetic skeleton of the namespace header,
     * the enclosing type declarations, this type's declaration lines (up to the
     * opening brace), the first line of each member, and the closing braces.
     */
    private void emitTypeFrame(ParserRuleContext ctx, String fqn, String name, String parentFqn,
                               List<? extends ParserRuleContext> members, List<PxChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        boolean nsOpen = !currentNamespace.isEmpty();
        if (nsOpen) {
            sb.append("namespace ").append(currentNamespace).append(" {\n");
        }
        int openBraces = 0;
        for (ParserRuleContext t : typeContexts) {
            if (appendDeclLines(sb, t)) {
                openBraces++;
            }
        }
        for (ParserRuleContext m : members) {
            sb.append("    ").append(firstLine(m)).append('\n');
        }
        for (int i = 0; i < openBraces; i++) {
            sb.append("}\n");
        }
        if (nsOpen) {
            sb.append("}\n");
        }
        int from0 = ctx.getStart().getLine() - 1;
        int to0 = ctx.getStop().getLine();
        chunks.addAll(splitter.splitContent(sb.toString(), from0, to0, () -> PxChunk.create(
                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                c -> c.setId(fqn),
                c -> c.setParent(parentFqn),
                c -> c.setFile(absPath),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.TYPE_FRAME.getName()),
                c -> SymbolMetadata.applyClass(c.getMetadata(), CSharpCodeSection.TYPE_FRAME.getName(), fqn, name)
        )));
    }

    /**
     * Emits the member chunk (and its doc chunk) for one member context.
     *
     * @param idSuffix the id part after the container FQN, e.g. {@code Add(int a, int b)} or {@code Count}
     */
    private void emitMember(ParserRuleContext ctx, CSharpCodeSection section, String idSuffix,
                            String memberName, String declaration, List<PxChunk> chunks) {
        String container = containerFqn();
        String id = container == null ? idSuffix : container + "." + idSuffix;
        int from0 = ctx.getStart().getLine() - 1;
        int to0 = ctx.getStop().getLine();
        String content = lines(from0, to0);
        emitDocChunk(ctx, id, CSharpCodeSection.METHOD_DOC,
                meta -> SymbolMetadata.applyMethod(meta, CSharpCodeSection.METHOD_DOC.getName(),
                        container == null ? "" : container, memberName, declaration), chunks);
        chunks.addAll(splitter.splitContent(content, from0, to0, () -> PxChunk.create(
                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                c -> c.setId(id),
                c -> c.setParent(container),
                c -> c.setFile(absPath),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, section.getName()),
                c -> SymbolMetadata.applyMethod(c.getMetadata(), section.getName(),
                        container == null ? "" : container, memberName, declaration)
        )));
    }

    /**
     * Emits a {@code <symbolId>.doc} chunk for the XML doc comment directly above
     * the symbol's start line (skipping attribute lines). No-op when there is no
     * doc comment there.
     */
    private void emitDocChunk(ParserRuleContext ctx, String symbolId, CSharpCodeSection docSection,
                              Consumer<Map<String, String>> metadataApplier, List<PxChunk> chunks) {
        int start0 = ctx.getStart().getLine() - 1;
        int docStart0 = -1;
        int i = start0 - 1;
        while (i >= 0) {
            String line = codeLines[i].trim();
            if (line.startsWith("///") || line.startsWith("/*") || line.startsWith("*")) {
                docStart0 = i;
                i--;
            } else if (line.startsWith("[")) {
                i--; // skip attribute lines
            } else {
                break;
            }
        }
        if (docStart0 < 0) {
            return; // no doc lines found
        }
        String content = lines(docStart0, start0);
        chunks.addAll(splitter.splitContent(content, docStart0, start0, () -> PxChunk.create(
                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                c -> c.setId(symbolId + ".doc"),
                c -> c.setParent(symbolId),
                c -> c.setFile(absPath),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, docSection.getName()),
                c -> metadataApplier.accept(c.getMetadata())
        )));
    }

    /** The usings chunk (position 0 of the file's chunks), anchored at the first type. */
    private List<PxChunk> createUsingsChunks(List<CSharpParser.Using_directiveContext> usings) {
        int from0 = Integer.MAX_VALUE;
        int to0 = 0;
        for (CSharpParser.Using_directiveContext u : usings) {
            from0 = Math.min(from0, u.getStart().getLine() - 1);
            to0 = Math.max(to0, u.getStop().getLine());
        }
        String idBase = firstTypeFqn != null ? firstTypeFqn : new File(absPath).getName();
        String simpleName = firstTypeName != null ? firstTypeName : new File(absPath).getName();
        String content = lines(from0, to0);
        return splitter.splitContent(content, from0, to0, () -> PxChunk.create(
                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                c -> c.setId(idBase + ".usings"),
                c -> c.setParent(firstTypeFqn),
                c -> c.setFile(absPath),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.USINGS.getName()),
                c -> SymbolMetadata.applyClass(c.getMetadata(), CSharpCodeSection.USINGS.getName(),
                        idBase, simpleName)
        ));
    }

    /** Whole-file fallback (parse error / exception): line-based parts, no semantics. */
    private Stream<PxChunk> rawFallback(String text) {
        if (text.isBlank()) {
            return Stream.empty();
        }
        List<PxChunk> parts = splitter.splitContent(text, 0, codeLines.length, () -> PxChunk.create(
                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                c -> c.setId(absPath + ".raw"),
                c -> c.setFile(absPath),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.RAW_FILE.getName())
        ));
        return parts.stream();
    }

    // ------------------------------------------------------------------
    // Scope / FQN helpers
    // ------------------------------------------------------------------

    /** The FQN of the innermost enclosing type ("" when the member is top-level). */
    private String containerFqn() {
        return fqn();
    }

    /** Dot-joins the current namespace, the type stack (outermost first), and any extra segments. */
    private String fqn(String... extra) {
        List<String> parts = new ArrayList<>();
        if (!currentNamespace.isEmpty()) {
            parts.add(currentNamespace);
        }
        parts.addAll(typeNames);
        for (String e : extra) {
            parts.add(e);
        }
        return String.join(".", parts);
    }

    // ------------------------------------------------------------------
    // Text extraction helpers
    // ------------------------------------------------------------------

    /** Joins source lines {@code [from0, to0)} (0-based, to exclusive) with {@code \n}. */
    private String lines(int from0, int to0) {
        int from = Math.max(0, from0);
        int to = Math.min(codeLines.length, to0);
        if (from >= to) {
            return "";
        }
        return String.join("\n", Arrays.copyOfRange(codeLines, from, to));
    }

    /** The first source line of a context, trimmed. */
    private String firstLine(ParserRuleContext ctx) {
        int line0 = ctx.getStart().getLine() - 1;
        if (line0 < 0 || line0 >= codeLines.length) {
            return "";
        }
        return codeLines[line0].trim();
    }

    /**
     * Appends the declaration lines of a type context: from its start line
     * (including attributes) up to and including the line with the opening body
     * brace — or up to its stop line when the declaration has no body.
     *
     * @return true if an opening brace line was included
     */
    private boolean appendDeclLines(StringBuilder sb, ParserRuleContext ctx) {
        int from0 = ctx.getStart().getLine() - 1;
        int end0 = Math.min(ctx.getStop().getLine() - 1, codeLines.length - 1);
        boolean hasBrace = false;
        for (int i = from0; i <= end0; i++) {
            if (!hasBrace && codeLines[i].contains("{")) {
                hasBrace = true;
            }
            sb.append(codeLines[i]).append('\n');
            if (hasBrace) {
                break;
            }
        }
        return hasBrace;
    }

    /** The context text with whitespace collapsed (input for the signature hash). */
    private String collapsed(ParserRuleContext ctx) {
        return ctx.getText().replaceAll("\\s+", " ").trim();
    }

    private String firstDeclaratorName(CSharpParser.Variable_declarationContext vd) {
        if (vd == null) {
            return null;
        }
        List<CSharpParser.Variable_declaratorContext> declarators = vd.variable_declarator();
        if (declarators.isEmpty()) {
            return null;
        }
        TerminalNode name = declarators.get(0).IDENTIFIER_TOKEN();
        return name == null ? null : name.getText();
    }

    private String paramListText(CSharpParser.Parameter_listContext pl) {
        if (pl == null) {
            return "";
        }
        return paramListText(pl.parameter());
    }

    private String paramListText(CSharpParser.Bracketed_parameter_listContext bpl) {
        if (bpl == null) {
            return "";
        }
        return paramListText(bpl.parameter());
    }

    private String paramListText(List<CSharpParser.ParameterContext> params) {
        if (params.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (CSharpParser.ParameterContext p : params) {
            parts.add(paramText(p));
        }
        return String.join(", ", parts);
    }

    /** {@code type name} of one parameter (attributes/modifiers/defaults excluded). */
    private String paramText(CSharpParser.ParameterContext p) {
        StringBuilder sb = new StringBuilder();
        if (p.type() != null) {
            sb.append(p.type().getText());
        }
        TerminalNode name = p.IDENTIFIER_TOKEN();
        if (name != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name.getText());
        } else if (p.ARGLIST() != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.ARGLIST().getText());
        }
        return sb.toString();
    }

    /** Error listener that counts syntax errors instead of printing them. */
    private static class ErrorCountingListener extends BaseErrorListener {
        int errorCount = 0;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            errorCount++;
        }
    }
}
