package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.lpcsharp.grammar.CSharpLexer;
import de.spraener.prjxp.lpcsharp.grammar.CSharpParser;
import de.spraener.prjxp.lpcsharp.grammar.CSharpParserBaseVisitor;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-2 Phase 01: smoke tests for the ANTLR-generated C# parser.
 *
 * <p>These verify that the parser (generated from the MIT-licensed
 * antlr/grammars-v4 csharp/v7 grammar) can handle the basic C# constructs the
 * chunker will need: a valid file, namespaces, classes, methods, and that it
 * reports syntax errors. No chunking happens here (that is Phase 02).
 */
class CSharpParserTest {

    /** Parses C# source and returns the compilation unit plus any syntax errors. */
    record ParseResult(CSharpParser.Compilation_unitContext tree, List<String> errors) {
        boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private static ParseResult parse(String source) {
        CSharpLexer lexer = new CSharpLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CSharpParser parser = new CSharpParser(tokens);
        parser.removeErrorListeners();
        CollectingErrorListener listener = new CollectingErrorListener();
        parser.addErrorListener(listener);
        CSharpParser.Compilation_unitContext tree = parser.compilation_unit();
        return new ParseResult(tree, listener.errors);
    }

    private static ParseResult parseFixture(String name) throws IOException {
        String source = Files.readString(Path.of("src/test/resources/fixtures", name));
        return parse(source);
    }

    /** Collects the top-level structure (namespaces, classes, methods) of a parse tree. */
    static class Structure extends CSharpParserBaseVisitor<Void> {
        final List<String> namespaces = new ArrayList<>();
        final List<String> classes = new ArrayList<>();
        final List<String> methods = new ArrayList<>();

        @Override
        public Void visitNamespace_declaration(CSharpParser.Namespace_declarationContext ctx) {
            namespaces.add(ctx.qualified_identifier().getText());
            return super.visitNamespace_declaration(ctx);
        }

        @Override
        public Void visitClass_definition(CSharpParser.Class_definitionContext ctx) {
            classes.add(ctx.identifier().getText());
            return super.visitClass_definition(ctx);
        }

        @Override
        public Void visitMethod_declaration(CSharpParser.Method_declarationContext ctx) {
            methods.add(ctx.method_member_name().getText());
            return super.visitMethod_declaration(ctx);
        }
    }

    private static Structure structure(CSharpParser.Compilation_unitContext tree) {
        Structure s = new Structure();
        s.visit(tree);
        return s;
    }

    @Test
    void parsesValidFileWithoutErrors() throws IOException {
        ParseResult result = parseFixture("ValidFile.cs");

        assertThat(result.hasErrors())
                .as("a valid C# file must parse without syntax errors: %s", result.errors)
                .isFalse();
        assertThat(result.tree()).isNotNull();
    }

    @Test
    void extractsNamespace() throws IOException {
        ParseResult result = parseFixture("Namespace.cs");

        assertThat(result.hasErrors()).isFalse();
        Structure s = structure(result.tree());
        assertThat(s.namespaces).containsExactly("Foo.Bar");
    }

    @Test
    void extractsClass() throws IOException {
        ParseResult result = parseFixture("Class.cs");

        assertThat(result.hasErrors()).isFalse();
        Structure s = structure(result.tree());
        assertThat(s.classes).containsExactly("StandaloneClass");
    }

    @Test
    void extractsMethod() throws IOException {
        ParseResult result = parseFixture("Method.cs");

        assertThat(result.hasErrors()).isFalse();
        Structure s = structure(result.tree());
        assertThat(s.classes).containsExactly("Container");
        assertThat(s.methods).containsExactly("DoWork");
    }

    @Test
    void reportsSyntaxError() throws IOException {
        ParseResult result = parseFixture("SyntaxError.cs");

        assertThat(result.hasErrors())
                .as("a file with a syntax error must be reported")
                .isTrue();
        assertThat(result.errors()).isNotEmpty();
    }

    /** Error listener that collects syntax errors instead of printing them. */
    static class CollectingErrorListener extends BaseErrorListener {
        final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            String text = offendingSymbol instanceof Token t ? t.getText() : String.valueOf(offendingSymbol);
            errors.add(line + ":" + charPositionInLine + " " + msg + " (" + text + ")");
        }
    }
}
