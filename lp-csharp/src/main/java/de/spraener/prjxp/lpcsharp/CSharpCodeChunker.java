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
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Splits C# source files into hierarchical {@link PxChunk}s:
 * usings, type frames (skeleton), methods, and constructors.
 * Falls back to raw line-based parts when parsing fails.
 */
public class CSharpCodeChunker implements PxChunker {

    public static final String CSHARP_CODE_MIME_TYPE = "text/x-csharp-code";

    private final int chunkSize;
    private final int overlap;

    public CSharpCodeChunker() {
        this(1000, 100);
    }

    public CSharpCodeChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public boolean matches(File f) {
        return PxFileType.C_SHARP_CODE.matches(f);
    }

    @Override
    public Stream<PxChunk> chunk(File f) {
        try {
            List<String> codeLines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            String source = String.join("\n", codeLines);

            CSharpLexer lexer = new CSharpLexer(CharStreams.fromString(source));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            CSharpParser parser = new CSharpParser(tokens);
            parser.removeErrorListeners();
            CollectingErrorListener errorListener = new CollectingErrorListener();
            parser.addErrorListener(errorListener);
            CSharpParser.Compilation_unitContext tree = parser.compilation_unit();

            if (errorListener.hasErrors()) {
                return rawFallback(f, codeLines);
            }

            ChunkCollector collector = new ChunkCollector(f, codeLines, chunkSize, overlap);
            collector.collect(tree);
            return collector.chunks().stream();
        } catch (Exception e) {
            try {
                return rawFallback(f, Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            } catch (IOException readError) {
                return Stream.of();
            }
        }
    }

    private Stream<PxChunk> rawFallback(File f, List<String> codeLines) {
        String content = String.join("\n", codeLines);
        int totalLines = codeLines.size();
        List<PxChunk> parts = new ContentSplitter(chunkSize, overlap)
                .splitContent(content, 0, totalLines,
                        () -> PxChunk.create(
                                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                                c -> c.setId(f.getAbsolutePath() + ".raw"),
                                c -> c.setFile(f.getAbsolutePath()),
                                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                        CSharpCodeSection.RAW_FILE.getName())
                        ));
        return parts.stream();
    }

    // ------------------------------------------------------------------
    // Tree-walking collector
    // ------------------------------------------------------------------

    private class ChunkCollector {
        private final File file;
        private final List<String> codeLines;
        private final int chunkSize;
        private final int overlap;
        private final List<PxChunk> chunks = new ArrayList<>();

        private String currentNamespace = "";
        private final Deque<String> typeNames = new ArrayDeque<>();
        private String firstTypeFqn;
        private final List<CSharpParser.Using_directivesContext> usingsBuffers = new ArrayList<>();

        ChunkCollector(File file, List<String> codeLines, int chunkSize, int overlap) {
            this.file = file;
            this.codeLines = codeLines;
            this.chunkSize = chunkSize;
            this.overlap = overlap;
        }

        List<PxChunk> chunks() {
            return chunks;
        }

        void collect(CSharpParser.Compilation_unitContext tree) {
            if (tree.using_directives() != null) {
                usingsBuffers.add(tree.using_directives());
            }
            if (tree.namespace_member_declarations() != null) {
                visitNamespaceMembers(tree.namespace_member_declarations());
            }
            finalizeUsings();
        }

        private void visitNamespaceMembers(CSharpParser.Namespace_member_declarationsContext decls) {
            for (CSharpParser.Namespace_member_declarationContext decl : decls.namespace_member_declaration()) {
                if (decl.namespace_declaration() != null) {
                    visitNamespace(decl.namespace_declaration());
                } else if (decl.type_declaration() != null) {
                    visitTypeDefFromWrapper(decl.type_declaration());
                }
            }
        }

        private void visitNamespace(CSharpParser.Namespace_declarationContext ns) {
            String nsName = ns.qualified_identifier().getText();
            String newNs = currentNamespace.isEmpty() ? nsName : currentNamespace + "." + nsName;
            String oldNs = currentNamespace;
            currentNamespace = newNs;

            CSharpParser.Namespace_bodyContext body = ns.namespace_body();
            if (body != null) {
                if (body.using_directives() != null) {
                    usingsBuffers.add(body.using_directives());
                }
                if (body.namespace_member_declarations() != null) {
                    visitNamespaceMembers(body.namespace_member_declarations());
                }
            }
            currentNamespace = oldNs;
        }

        private void visitTypeDefFromWrapper(CSharpParser.Type_declarationContext wrapper) {
            if (wrapper.class_definition() != null) visitTypeDef(wrapper.class_definition(), wrapper);
            else if (wrapper.struct_definition() != null) visitTypeDef(wrapper.struct_definition(), wrapper);
            else if (wrapper.interface_definition() != null) visitTypeDef(wrapper.interface_definition(), wrapper);
            else if (wrapper.enum_definition() != null) visitTypeDef(wrapper.enum_definition(), wrapper);
            else if (wrapper.record_definition() != null) visitTypeDef(wrapper.record_definition(), wrapper);
            // delegate: no body, skip
        }

        /**
         * @param typeDef  the specific type definition (class/struct/...), used for name + body
         * @param declCtx  the full declaration (attributes + modifiers + type), used for the line range
         */
        private void visitTypeDef(ParserRuleContext typeDef, ParserRuleContext declCtx) {
            String name = typeSimpleName(typeDef);
            String fqn = computeFqn(name);
            if (firstTypeFqn == null) {
                firstTypeFqn = fqn;
            }
            ParserRuleContext body = typeBodyOf(typeDef);
            String parent = typeNames.isEmpty() ? null : containerFqn();

            createTypeFrame(fqn, name, typeDef, body, parent, declCtx);

            typeNames.push(name);
            if (body != null) {
                visitTypeBody(body, fqn, name, parent);
            }
            typeNames.pop();
        }

        private void visitTypeBody(ParserRuleContext body, String fqn, String name, String parent) {
            if (body instanceof CSharpParser.Class_bodyContext cb) {
                if (cb.class_member_declarations() != null) {
                    for (CSharpParser.Class_member_declarationContext m : cb.class_member_declarations().class_member_declaration()) {
                        visitCommonMember(m, m.common_member_declaration());
                    }
                }
            } else if (body instanceof CSharpParser.Struct_bodyContext sb) {
                for (CSharpParser.Struct_member_declarationContext m : sb.struct_member_declaration()) {
                    visitCommonMember(m, m.common_member_declaration());
                }
            } else if (body instanceof CSharpParser.Enum_bodyContext eb) {
                createEnumConstants(fqn, name, eb, parent);
            }
        }

        /**
         * @param memberDecl the full member declaration (attributes + modifiers + member),
         *                   used for the chunk's line range so modifiers are preserved
         */
        private void visitCommonMember(ParserRuleContext memberDecl, CSharpParser.Common_member_declarationContext cmd) {
            if (cmd == null) return;

            // Nested types
            if (cmd.class_definition() != null) { visitTypeDef(cmd.class_definition(), memberDecl); return; }
            if (cmd.struct_definition() != null) { visitTypeDef(cmd.struct_definition(), memberDecl); return; }
            if (cmd.interface_definition() != null) { visitTypeDef(cmd.interface_definition(), memberDecl); return; }
            if (cmd.enum_definition() != null) { visitTypeDef(cmd.enum_definition(), memberDecl); return; }
            if (cmd.record_definition() != null) { visitTypeDef(cmd.record_definition(), memberDecl); return; }

            // Constructors
            if (cmd.constructor_declaration() != null) {
                createConstructor(containerFqn(), cmd.constructor_declaration(), memberDecl);
                return;
            }

            // Events
            if (cmd.event_declaration() != null) {
                createEvent(containerFqn(), cmd.event_declaration(), memberDecl);
                return;
            }

            // Void methods (directly under common_member_declaration)
            if (cmd.method_declaration() != null) {
                createMethod(containerFqn(), cmd.method_declaration(), memberDecl);
                return;
            }

            // Typed members (methods, properties, indexers, fields)
            if (cmd.typed_member_declaration() != null) {
                CSharpParser.Typed_member_declarationContext tmd = cmd.typed_member_declaration();
                if (tmd.method_declaration() != null) {
                    createMethod(containerFqn(), tmd.method_declaration(), memberDecl);
                } else if (tmd.property_declaration() != null) {
                    createProperty(containerFqn(), tmd, memberDecl);
                } else if (tmd.indexer_declaration() != null) {
                    createIndexer(containerFqn(), tmd, memberDecl);
                } else if (tmd.field_declaration() != null) {
                    createField(containerFqn(), tmd, memberDecl);
                }
                // operators: non-goal
            }
        }

        // ------------------------------------------------------------------
        // Chunk creation
        // ------------------------------------------------------------------

        /**
         * Creates the buffered usings chunks, associating them with the first type
         * of the file (analog of the Java chunker's imports → primary class).
         */
        private void finalizeUsings() {
            if (usingsBuffers.isEmpty()) {
                return;
            }
            String base = firstTypeFqn != null ? firstTypeFqn : file.getName();
            String id = base + ".usings";
            String parent = firstTypeFqn;
            List<PxChunk> usingsChunks = new ArrayList<>();
            for (CSharpParser.Using_directivesContext usings : usingsBuffers) {
                int fromLine = usings.getStart().getLine() - 1;
                int toLine = usings.getStop().getLine();
                String content = extractLines(fromLine, toLine);
                usingsChunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                        () -> PxChunk.create(
                                c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                                c -> c.setId(id),
                                c -> c.setFile(file.getAbsolutePath()),
                                c -> { if (parent != null) c.setParent(parent); },
                                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                        CSharpCodeSection.USINGS.getName())
                        )));
            }
            chunks.addAll(0, usingsChunks);
        }

        private void createTypeFrame(String fqn, String name, ParserRuleContext typeDef,
                                     ParserRuleContext body, String parent, ParserRuleContext declCtx) {
            int fromLine = declCtx.getStart().getLine() - 1;
            int toLine = declCtx.getStop().getLine();

            StringBuilder content = new StringBuilder();
            if (!currentNamespace.isEmpty()) {
                content.append("namespace ").append(currentNamespace).append(" {\n");
            }

            // Declaration: from first attribute/modifier to opening brace (or end if no body)
            int braceLine = (body != null) ? body.getStart().getLine() - 1 : toLine - 1;
            for (int i = fromLine; i <= braceLine && i < codeLines.size(); i++) {
                content.append(codeLines.get(i)).append('\n');
            }

            // Member signature lines (first line of each member)
            if (body != null) {
                for (ParserRuleContext member : memberList(body)) {
                    int mLine = member.getStart().getLine() - 1;
                    if (mLine >= 0 && mLine < codeLines.size()) {
                        content.append("    ").append(codeLines.get(mLine)).append('\n');
                    }
                }
            }
            content.append("}\n");
            if (!currentNamespace.isEmpty()) {
                content.append("}\n");
            }

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content.toString(), fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(fqn),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> { if (parent != null) c.setParent(parent); },
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.TYPE_FRAME.getName()),
                            c -> SymbolMetadata.applyClass(c.getMetadata(),
                                    CSharpCodeSection.TYPE_FRAME.getName(), fqn, name)
                    )));

            createDocChunk(fqn, parent, name, CSharpCodeSection.TYPE_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createMethod(String containerFqn, CSharpParser.Method_declarationContext method,
                                  ParserRuleContext memberDecl) {
            String methodName = method.method_member_name().getText();
            String paramText = paramText(method.formal_parameter_list());
            String id = containerFqn + "." + methodName + "(" + paramText + ")";

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.METHOD.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.METHOD.getName(), containerFqn, methodName, declaration)
                    )));

            createDocChunk(id, containerFqn, methodName, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createConstructor(String containerFqn, CSharpParser.Constructor_declarationContext ctor,
                                       ParserRuleContext memberDecl) {
            String typeName = ctor.identifier().getText();
            String paramText = paramText(ctor.formal_parameter_list());
            String id = containerFqn + "." + typeName + "(" + paramText + ")";

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.CONSTRUCTOR.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.CONSTRUCTOR.getName(), containerFqn, typeName, declaration)
                    )));

            createDocChunk(id, containerFqn, typeName, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createField(String containerFqn, CSharpParser.Typed_member_declarationContext tmd,
                                 ParserRuleContext memberDecl) {
            CSharpParser.Field_declarationContext field = tmd.field_declaration();
            String name = field.variable_declarators().variable_declarator(0).identifier().getText();
            String id = containerFqn + "." + name;

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.FIELD.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.FIELD.getName(), containerFqn, name, declaration)
                    )));

            createDocChunk(id, containerFqn, name, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createProperty(String containerFqn, CSharpParser.Typed_member_declarationContext tmd,
                                    ParserRuleContext memberDecl) {
            CSharpParser.Property_declarationContext prop = tmd.property_declaration();
            String name = prop.member_name().getText();
            String id = containerFqn + "." + name;

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.PROPERTY.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.PROPERTY.getName(), containerFqn, name, declaration)
                    )));

            createDocChunk(id, containerFqn, name, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createIndexer(String containerFqn, CSharpParser.Typed_member_declarationContext tmd,
                                   ParserRuleContext memberDecl) {
            CSharpParser.Indexer_declarationContext idx = tmd.indexer_declaration();
            String name = "this";
            String paramText = idx.formal_parameter_list() != null
                    ? paramText(idx.formal_parameter_list()) : "";
            String id = containerFqn + "." + name + "(" + paramText + ")";

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.INDEXER.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.INDEXER.getName(), containerFqn, name, declaration)
                    )));

            createDocChunk(id, containerFqn, name, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        private void createEvent(String containerFqn, CSharpParser.Event_declarationContext event,
                                 ParserRuleContext memberDecl) {
            String name;
            if (event.variable_declarators() != null) {
                name = event.variable_declarators().variable_declarator(0).identifier().getText();
            } else {
                name = event.member_name().getText();
            }
            String id = containerFqn + "." + name;

            int fromLine = memberDecl.getStart().getLine() - 1;
            int toLine = memberDecl.getStop().getLine();
            String content = extractLines(fromLine, toLine);
            String declaration = content.replaceAll("\\s+", " ").trim();

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> c.setParent(containerFqn),
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.EVENT.getName()),
                            c -> SymbolMetadata.applyMethod(c.getMetadata(),
                                    CSharpCodeSection.EVENT.getName(), containerFqn, name, declaration)
                    )));

            createDocChunk(id, containerFqn, name, CSharpCodeSection.METHOD_DOC,
                    docAbove(fromLine), fromLine, toLine);
        }

        /**
         * Creates a single chunk holding all enum constants (analog of Java enumConstants).
         */
        private void createEnumConstants(String fqn, String name, ParserRuleContext enumBody, String parent) {
            int fromLine = enumBody.getStart().getLine() - 1;
            int toLine = enumBody.getStop().getLine();
            String content = extractLines(fromLine, toLine);

            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(content, fromLine, toLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(fqn + ".constants"),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> { if (parent != null) c.setParent(parent); },
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY,
                                    CSharpCodeSection.ENUM_CONSTANT.getName()),
                            c -> SymbolMetadata.applyClass(c.getMetadata(),
                                    CSharpCodeSection.ENUM_CONSTANT.getName(), fqn, name)
                    )));
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private String typeSimpleName(ParserRuleContext typeDef) {
            if (typeDef instanceof CSharpParser.Class_definitionContext c) return c.identifier().getText();
            if (typeDef instanceof CSharpParser.Struct_definitionContext s) return s.identifier().getText();
            if (typeDef instanceof CSharpParser.Interface_definitionContext i) return i.identifier().getText();
            if (typeDef instanceof CSharpParser.Enum_definitionContext e) return e.identifier().getText();
            if (typeDef instanceof CSharpParser.Record_definitionContext r) return r.identifier().getText();
            if (typeDef instanceof CSharpParser.Delegate_definitionContext d) return d.identifier().getText();
            return "?";
        }

        private ParserRuleContext typeBodyOf(ParserRuleContext typeDef) {
            if (typeDef instanceof CSharpParser.Class_definitionContext c) return c.class_body();
            if (typeDef instanceof CSharpParser.Struct_definitionContext s) return s.struct_body();
            if (typeDef instanceof CSharpParser.Interface_definitionContext i) return i.class_body();
            if (typeDef instanceof CSharpParser.Enum_definitionContext e) return e.enum_body();
            if (typeDef instanceof CSharpParser.Record_definitionContext r) return r.class_body();
            return null; // delegate
        }

        private List<ParserRuleContext> memberList(ParserRuleContext body) {
            List<ParserRuleContext> members = new ArrayList<>();
            if (body instanceof CSharpParser.Class_bodyContext cb) {
                if (cb.class_member_declarations() != null) {
                    for (CSharpParser.Class_member_declarationContext m : cb.class_member_declarations().class_member_declaration()) {
                        members.add(m);
                    }
                }
            } else if (body instanceof CSharpParser.Struct_bodyContext sb) {
                for (CSharpParser.Struct_member_declarationContext m : sb.struct_member_declaration()) {
                    members.add(m);
                }
            } else if (body instanceof CSharpParser.Enum_bodyContext eb) {
                for (CSharpParser.Enum_member_declarationContext m : eb.enum_member_declaration()) {
                    members.add(m);
                }
            }
            return members;
        }

        private String computeFqn(String name) {
            List<String> names = new ArrayList<>(typeNames);
            Collections.reverse(names); // outermost first
            names.add(name);
            StringBuilder sb = new StringBuilder();
            if (!currentNamespace.isEmpty()) sb.append(currentNamespace);
            for (String n : names) {
                if (sb.length() > 0) sb.append('.');
                sb.append(n);
            }
            return sb.toString();
        }

        private String containerFqn() {
            if (typeNames.isEmpty()) return currentNamespace;
            List<String> names = new ArrayList<>(typeNames);
            Collections.reverse(names); // outermost first
            StringBuilder sb = new StringBuilder();
            if (!currentNamespace.isEmpty()) sb.append(currentNamespace);
            for (String n : names) {
                if (sb.length() > 0) sb.append('.');
                sb.append(n);
            }
            return sb.toString();
        }

        private String extractLines(int fromLine, int toLine) {
            StringBuilder sb = new StringBuilder();
            for (int i = fromLine; i < toLine && i < codeLines.size(); i++) {
                sb.append(codeLines.get(i)).append('\n');
            }
            return sb.toString();
        }

        /**
         * The parameter list text as written in the source (whitespace preserved,
         * then normalized to single spaces) — unlike {@code ParserRuleContext#getText()}
         * which concatenates tokens without separators.
         */
        private String paramText(CSharpParser.Formal_parameter_listContext params) {
            if (params == null) {
                return "";
            }
            String raw = extractTokenRange(params.getStart(), params.getStop());
            return raw.replaceAll("\\s+", " ").trim();
        }

        private String extractTokenRange(Token start, Token stop) {
            int startLine = start.getLine() - 1;
            int startCol = start.getCharPositionInLine();
            int stopLine = stop.getLine() - 1;
            int stopEnd = stop.getCharPositionInLine() + stop.getText().length();

            if (startLine == stopLine) {
                String line = codeLines.get(startLine);
                return line.substring(Math.min(startCol, line.length()), Math.min(stopEnd, line.length()));
            }
            StringBuilder sb = new StringBuilder();
            String first = codeLines.get(startLine);
            sb.append(first.substring(Math.min(startCol, first.length())));
            for (int i = startLine + 1; i < stopLine && i < codeLines.size(); i++) {
                sb.append('\n').append(codeLines.get(i));
            }
            if (stopLine < codeLines.size()) {
                String last = codeLines.get(stopLine);
                sb.append('\n').append(last.substring(0, Math.min(stopEnd, last.length())));
            }
            return sb.toString();
        }

        /**
         * Collects the XML documentation comment (single-line /// or delimited blocks)
         * directly above the given 0-based start line. Stops at the first non-doc,
         * non-blank line; single-line attributes ([...]) are skipped so a doc above an
         * attribute still binds to the construct below it.
         */
        private String docAbove(int startLine0Based) {
            StringBuilder doc = new StringBuilder();
            int i = startLine0Based - 1;
            while (i >= 0) {
                String trimmed = codeLines.get(i).trim();
                if (trimmed.startsWith("///") || trimmed.startsWith("/**")
                        || trimmed.startsWith("*") || trimmed.equals("*/")) {
                    doc.insert(0, codeLines.get(i) + "\n");
                    i--;
                } else if (trimmed.isEmpty()) {
                    break;
                } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    i--;
                } else {
                    break;
                }
            }
            return doc.toString();
        }

        /**
         * Creates a documentation chunk bound to the given symbol, if the doc text is non-empty.
         *
         * @param symbolId the id of the documented symbol (the doc chunk id becomes {@code symbolId + ".doc"})
         * @param parent   the parent of the doc chunk (the documented symbol's parent)
         * @param name     the documented symbol's simple name
         */
        private void createDocChunk(String symbolId, String parent, String name,
                                    CSharpCodeSection docSection, String doc, int symbolFromLine, int symbolToLine) {
            if (doc.isBlank()) {
                return;
            }
            String id = symbolId + ".doc";
            // The doc sits directly above the symbol: its 0-based lines are
            // [symbolFromLine - docLineCount, symbolFromLine - 1].
            long docLineCount = doc.lines().count();
            int docFromLine = symbolFromLine - (int) docLineCount;
            int docToLine = symbolFromLine; // 1-based last doc line
            chunks.addAll(new ContentSplitter(chunkSize, overlap).splitContent(doc, docFromLine, docToLine,
                    () -> PxChunk.create(
                            c -> c.setMimeType(CSHARP_CODE_MIME_TYPE),
                            c -> c.setId(id),
                            c -> c.setFile(file.getAbsolutePath()),
                            c -> { if (parent != null) c.setParent(parent); },
                            c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, docSection.getName()),
                            c -> c.getMetadata().put(SymbolMetadata.SYMBOL_NAME, name),
                            c -> c.getMetadata().put(SymbolMetadata.SYMBOL_TYPE, docSection.getName())
                    )));
        }
    }

    // ------------------------------------------------------------------
    // Error listener
    // ------------------------------------------------------------------

    static class CollectingErrorListener extends BaseErrorListener {
        private final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            errors.add(line + ":" + charPositionInLine + " " + msg);
        }

        boolean hasErrors() {
            return !errors.isEmpty();
        }

        List<String> getErrors() {
            return errors;
        }
    }
}
