package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.lpcsharp.grammar.CSharpLexer;
import de.spraener.prjxp.lpcsharp.grammar.CSharpParser;
import de.spraener.prjxp.lpcsharp.grammar.CSharpBaseVisitor;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C# grammar upgrade Phase 01: smoke tests for the Roslyn-derived parser
 * (generated from the combined CSharp.g4 grammar).
 *
 * <p>The hand-adapted grammar was never built before this phase, so these
 * verify the four risk areas: basic C# 7, C# 14 headline types (record +
 * union), `#` preprocessor directives (the DIRECTIVE skip rule), and
 * multi-line string/comment tokens.
 */
class CSharpParserSmokeTest {

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

    /** Counts the Roslyn context types the chunker will walk. */
    static class Counter extends CSharpBaseVisitor<Void> {
        final List<String> classes = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final List<String> records = new ArrayList<>();
        final List<String> unions = new ArrayList<>();

        @Override
        public Void visitClass_declaration(CSharpParser.Class_declarationContext ctx) {
            classes.add(ctx.getText());
            return super.visitClass_declaration(ctx);
        }

        @Override
        public Void visitMethod_declaration(CSharpParser.Method_declarationContext ctx) {
            methods.add(ctx.getText());
            return super.visitMethod_declaration(ctx);
        }

        @Override
        public Void visitRecord_declaration(CSharpParser.Record_declarationContext ctx) {
            records.add(ctx.getText());
            return super.visitRecord_declaration(ctx);
        }

        @Override
        public Void visitUnion_declaration(CSharpParser.Union_declarationContext ctx) {
            unions.add(ctx.getText());
            return super.visitUnion_declaration(ctx);
        }
    }

    private static Counter count(CSharpParser.Compilation_unitContext tree) {
        Counter c = new Counter();
        c.visit(tree);
        return c;
    }

    @Test
    void parsesCSharp7File() {
        ParseResult result = parse(
                "namespace N {\n" +
                "    class C {\n" +
                "        int M() { return 1; }\n" +
                "    }\n" +
                "}\n");

        assertThat(result.hasErrors())
                .as("a valid C# 7 file must parse without syntax errors: %s", result.errors)
                .isFalse();
        Counter c = count(result.tree());
        assertThat(c.classes).hasSize(1);
        assertThat(c.methods).hasSize(1);
    }

    @Test
    void parsesCSharp14RecordAndUnion() {
        ParseResult result = parse(
                "record Person(string Name, int Age);\n" +
                "union Shape {\n" +
                "    int I;\n" +
                "    string S;\n" +
                "}\n");

        assertThat(result.hasErrors())
                .as("a C# 14 record + union must parse without syntax errors: %s", result.errors)
                .isFalse();
        Counter c = count(result.tree());
        assertThat(c.records).hasSize(1);
        assertThat(c.unions).hasSize(1);
    }

    @Test
    void parsesHashDirectivesAndKeepsInnerCode() {
        ParseResult result = parse(
                "class C {\n" +
                "    #region Methods\n" +
                "    void M() { }\n" +
                "    #endregion\n" +
                "    #if DEBUG\n" +
                "    void N() { }\n" +
                "    #endif\n" +
                "}\n");

        assertThat(result.hasErrors())
                .as("a file with # directives must parse without syntax errors: %s", result.errors)
                .isFalse();
        Counter c = count(result.tree());
        assertThat(c.classes).hasSize(1);
        // Both methods — including the one inside #if — must be present.
        assertThat(c.methods).hasSize(2);
    }

    @Test
    void parsesMultiLineRawStringAndBlockComment() {
        ParseResult result = parse(
                "class C {\n" +
                "    string S = \"\"\"\n" +
                "        line one\n" +
                "        line two\n" +
                "        \"\"\";\n" +
                "    string T = /* multi\n" +
                "        line comment */ \"x\";\n" +
                "}\n");

        assertThat(result.hasErrors())
                .as("a multi-line raw string + block comment must parse: %s", result.errors)
                .isFalse();
    }

    /** Error listener that collects syntax errors instead of printing them. */
    static class CollectingErrorListener extends BaseErrorListener {
        final List<String> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            errors.add(line + ":" + charPositionInLine + " " + msg);
        }
    }
}
