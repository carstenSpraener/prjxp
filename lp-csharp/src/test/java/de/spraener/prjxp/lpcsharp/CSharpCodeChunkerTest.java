package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C# grammar upgrade Phase 02: chunker tests against the Roslyn-derived parse
 * tree. The 17 C# 7 fixtures must identify the same symbols as the v7 chunker
 * (same sections, same FQNs); chunk line boundaries may shift.
 */
class CSharpCodeChunkerTest {

    private final CSharpCodeChunker chunker = new CSharpCodeChunker();

    private List<PxChunk> chunkFixture(String name) {
        File f = new File("src/test/resources/fixtures/" + name);
        assertThat(f).exists();
        return chunker.chunk(f).collect(Collectors.toList());
    }

    @TempDir
    Path tempDir;

    /** Writes {@code content} to a new {@code .cs} file in the temp dir and returns its path. */
    private Path tempFile(String name, String content) throws IOException {
        Path f = tempDir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static List<PxChunk> sections(List<PxChunk> chunks, CSharpCodeSection section) {
        return chunks.stream()
                .filter(c -> section.getName().equals(c.getMetadata().get(CSharpCodeSection.METADATA_KEY)))
                .collect(Collectors.toList());
    }

    private static Map<String, PxChunk> byId(List<PxChunk> chunks) {
        return chunks.stream().collect(Collectors.toMap(PxChunk::getId, c -> c, (a, b) -> a));
    }

    // ------------------------------------------------------------------
    // Usings / namespace
    // ------------------------------------------------------------------

    @Test
    void usingsAndNamespace_produceUsingsAndTypeFrameChunks() {
        List<PxChunk> chunks = chunkFixture("UsingsNamespace.cs");

        List<PxChunk> usings = sections(chunks, CSharpCodeSection.USINGS);
        assertThat(usings).hasSize(1);
        assertThat(usings.get(0).getId()).isEqualTo("Acme.Widgets.Widget.usings");
        assertThat(usings.get(0).getParent()).isEqualTo("Acme.Widgets.Widget");
        assertThat(usings.get(0).getContent()).contains("using System;");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.Widgets.Widget");
        assertThat(frames.get(0).getParent()).isNull();
        assertThat(frames.get(0).getContent()).contains("class Widget");
    }

    // ------------------------------------------------------------------
    // Type kinds
    // ------------------------------------------------------------------

    @Test
    void class_producesTypeFrameWithFqn() {
        List<PxChunk> chunks = chunkFixture("ClassMembers.cs");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.Calculator");
        assertThat(frames.get(0).getContent()).contains("class Calculator");
    }

    @Test
    void interface_producesTypeFrame() {
        List<PxChunk> chunks = chunkFixture("Interface.cs");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.IShape");
    }

    @Test
    void struct_producesTypeFrame() {
        List<PxChunk> chunks = chunkFixture("Struct.cs");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.Point");
    }

    @Test
    void record_producesTypeFrame() {
        List<PxChunk> chunks = chunkFixture("Record.cs");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.Person");
    }

    @Test
    void enum_producesTypeFrame() {
        List<PxChunk> chunks = chunkFixture("Enum.cs");

        List<PxChunk> frames = sections(chunks, CSharpCodeSection.TYPE_FRAME);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getId()).isEqualTo("Acme.Color");
    }

    // ------------------------------------------------------------------
    // Methods / constructors / overloads
    // ------------------------------------------------------------------

    @Test
    void methodsAndConstructors_produceOwnChunksWithParents() {
        List<PxChunk> chunks = chunkFixture("ClassMembers.cs");
        Map<String, PxChunk> byId = byId(chunks);

        // Two constructors
        assertThat(byId.keySet())
                .contains("Acme.Calculator.Calculator()", "Acme.Calculator.Calculator(int scale)");
        assertThat(byId.get("Acme.Calculator.Calculator()").getParent()).isEqualTo("Acme.Calculator");
        assertThat(byId.get("Acme.Calculator.Calculator()").getMetadata()
                .get(CSharpCodeSection.METADATA_KEY)).isEqualTo(CSharpCodeSection.CONSTRUCTOR.getName());

        // Two Add overloads → distinct IDs, both METHOD
        assertThat(byId.keySet())
                .contains("Acme.Calculator.Add(int a, int b)", "Acme.Calculator.Add(double a, double b)");
        assertThat(byId.get("Acme.Calculator.Add(int a, int b)").getMetadata()
                .get(CSharpCodeSection.METADATA_KEY)).isEqualTo(CSharpCodeSection.METHOD.getName());
        assertThat(byId.get("Acme.Calculator.Add(int a, int b)").getParent()).isEqualTo("Acme.Calculator");
        assertThat(byId.get("Acme.Calculator.Add(int a, int b)").getContent())
                .contains("return a + b;");
    }

    @Test
    void overloads_doNotCollide() {
        List<PxChunk> chunks = chunkFixture("ClassMembers.cs");

        List<String> addIds = chunks.stream()
                .map(PxChunk::getId)
                .filter(id -> id != null && id.contains("Add("))
                .collect(Collectors.toList());
        assertThat(addIds).hasSize(2);
        assertThat(addIds).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------

    @Test
    void nestedTypes_getQualifiedFqnAndParent() {
        List<PxChunk> chunks = chunkFixture("NestedTypes.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKeys("Acme.Outer", "Acme.Outer.Inner");

        PxChunk inner = byId.get("Acme.Outer.Inner");
        assertThat(inner.getParent()).isEqualTo("Acme.Outer");

        // Method of the nested type points at the nested type
        assertThat(byId).containsKey("Acme.Outer.Inner.InnerMethod()");
        assertThat(byId.get("Acme.Outer.Inner.InnerMethod()").getParent()).isEqualTo("Acme.Outer.Inner");

        // Method of the outer type points at the outer type
        assertThat(byId).containsKey("Acme.Outer.OuterMethod()");
        assertThat(byId.get("Acme.Outer.OuterMethod()").getParent()).isEqualTo("Acme.Outer");
    }

    // ------------------------------------------------------------------
    // Fallback
    // ------------------------------------------------------------------

    @Test
    void syntaxError_emitsBestEffortSemanticChunks() {
        List<PxChunk> chunks = chunkFixture("SyntaxError.cs");

        // The recovered tree still yields the top-level type, so we get a
        // best-effort semantic cut rather than a raw fallback.
        assertThat(sections(chunks, CSharpCodeSection.TYPE_FRAME)).isNotEmpty();
        assertThat(sections(chunks, CSharpCodeSection.RAW_FILE)).isEmpty();
    }

    @Test
    void contextualKeywordAsIdentifier_stillGetsSemanticCuts() throws IOException {
        // `get` is a contextual keyword; used as an identifier it used to break the
        // parse and (with the old all-or-nothing fallback) lose the whole file.
        Path f = tempFile("CtxKeyword.cs",
                "class C {\n    void M() {\n        var get = 1;\n    }\n}\n");
        List<PxChunk> chunks = chunker.chunk(f.toFile()).collect(Collectors.toList());

        assertThat(byId(chunks)).containsKey("C");
        assertThat(sections(chunks, CSharpCodeSection.RAW_FILE)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Phase 03: fields, properties, events, indexers, enum values, docs, metadata
    // ------------------------------------------------------------------

    @Test
    void fullClass_producesAllMemberSections() {
        List<PxChunk> chunks = chunkFixture("FullClass.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.FullClass");
        assertThat(byId).containsKey("Acme.FullClass.Count");
        assertThat(byId).containsKey("Acme.FullClass.Name");
        assertThat(byId).containsKey("Acme.FullClass.Changed");
        assertThat(byId).containsKey("Acme.FullClass.this(int index)");
        assertThat(byId).containsKey("Acme.FullClass.FullClass()");
        assertThat(byId).containsKey("Acme.FullClass.Add(int a, int b)");

        assertThat(byId.get("Acme.FullClass.Count").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.FIELD.getName());
        assertThat(byId.get("Acme.FullClass.Name").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.PROPERTY.getName());
        assertThat(byId.get("Acme.FullClass.Changed").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.EVENT.getName());
        assertThat(byId.get("Acme.FullClass.this(int index)").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.INDEXER.getName());

        // All members point at the type
        for (String id : List.of("Acme.FullClass.Count", "Acme.FullClass.Name",
                "Acme.FullClass.Changed", "Acme.FullClass.this(int index)")) {
            assertThat(byId.get(id).getParent()).isEqualTo("Acme.FullClass");
        }
    }

    @Test
    void fullClass_xmlDocAssociatedWithCorrectSymbols() {
        List<PxChunk> chunks = chunkFixture("FullClass.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.FullClass.doc");
        assertThat(byId.get("Acme.FullClass.doc").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.TYPE_DOC.getName());
        assertThat(byId.get("Acme.FullClass.doc").getContent()).contains("A fully-featured class.");

        assertThat(byId).containsKey("Acme.FullClass.Count.doc");
        assertThat(byId.get("Acme.FullClass.Count.doc").getContent()).contains("A public counter field.");

        assertThat(byId).containsKey("Acme.FullClass.Name.doc");
        assertThat(byId.get("Acme.FullClass.Name.doc").getContent()).contains("The display name.");

        assertThat(byId).containsKey("Acme.FullClass.Add(int a, int b).doc");
        assertThat(byId.get("Acme.FullClass.Add(int a, int b).doc").getContent()).contains("Adds two numbers.");
    }

    @Test
    void allChunks_haveValidOrderedLineReferences() {
        List<PxChunk> chunks = chunkFixture("FullClass.cs");
        assertThat(chunks).isNotEmpty();
        for (PxChunk c : chunks) {
            assertThat(c.getFromLine()).as("fromLine of %s", c.getId()).isNotBlank();
            assertThat(c.getToLine()).as("toLine of %s", c.getId()).isNotBlank();
            int from = Integer.parseInt(c.getFromLine());
            int to = Integer.parseInt(c.getToLine());
            assertThat(from).as("fromLine <= toLine for %s", c.getId()).isLessThanOrEqualTo(to);
        }
    }

    @Test
    void modifiersAndAttributes_arePreservedInMemberChunks() {
        List<PxChunk> chunks = chunkFixture("FullClass.cs");
        Map<String, PxChunk> byId = byId(chunks);

        // The method chunk includes the 'public' modifier (full member declaration)
        assertThat(byId.get("Acme.FullClass.Add(int a, int b)").getContent()).contains("public");
        // The field chunk includes the 'public' modifier
        assertThat(byId.get("Acme.FullClass.Count").getContent()).contains("public");
        // The type frame includes the [Serializable] attribute
        assertThat(byId.get("Acme.FullClass").getContent()).contains("[Serializable]");
    }

    @Test
    void fullClass_symbolMetadataIsDeterministic() {
        List<PxChunk> chunks = chunkFixture("FullClass.cs");
        Map<String, PxChunk> byId = byId(chunks);

        PxChunk frame = byId.get("Acme.FullClass");
        assertThat(frame.getMetadata().get(SymbolMetadata.SYMBOL_FQN)).isEqualTo("Acme.FullClass");
        assertThat(frame.getMetadata().get(SymbolMetadata.SYMBOL_NAME)).isEqualTo("FullClass");
        assertThat(frame.getMetadata().get(SymbolMetadata.SYMBOL_TYPE)).isEqualTo(CSharpCodeSection.TYPE_FRAME.getName());

        PxChunk method = byId.get("Acme.FullClass.Add(int a, int b)");
        assertThat(method.getMetadata().get(SymbolMetadata.SYMBOL_FQN)).isEqualTo("Acme.FullClass#Add");
        assertThat(method.getMetadata().get(SymbolMetadata.SYMBOL_NAME)).isEqualTo("Add");
        assertThat(method.getMetadata().get(SymbolMetadata.SYMBOL_CONTAINER_FQN)).isEqualTo("Acme.FullClass");
        assertThat(method.getMetadata().get(SymbolMetadata.SYMBOL_SIGNATURE_HASH)).isNotBlank();
    }

    @Test
    void enumValues_produceConstantsChunkAndDoc() {
        List<PxChunk> chunks = chunkFixture("EnumValues.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.RainbowColor");
        assertThat(byId).containsKey("Acme.RainbowColor.constants");
        assertThat(byId.get("Acme.RainbowColor.constants").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.ENUM_CONSTANT.getName());
        String constants = byId.get("Acme.RainbowColor.constants").getContent();
        assertThat(constants).contains("Red").contains("Green = 5").contains("Blue = 10").contains("Violet");

        assertThat(byId).containsKey("Acme.RainbowColor.doc");
        assertThat(byId.get("Acme.RainbowColor.doc").getContent()).contains("Colors of the rainbow.");
    }

    @Test
    void expressionBodiedMembers_produceChunks() {
        List<PxChunk> chunks = chunkFixture("ExpressionBodied.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.MathHelper.Double(int x)");
        assertThat(byId.get("Acme.MathHelper.Double(int x)").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.METHOD.getName());
        assertThat(byId.get("Acme.MathHelper.Double(int x)").getContent()).contains("x * 2");

        assertThat(byId).containsKey("Acme.MathHelper.Answer");
        assertThat(byId.get("Acme.MathHelper.Answer").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.PROPERTY.getName());

        assertThat(byId).containsKey("Acme.MathHelper.Greet(string name)");
    }

    @Test
    void generics_fqnExcludesTypeParameters() {
        List<PxChunk> chunks = chunkFixture("Generics.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Repository");
        assertThat(byId).containsKey("Acme.IRepository");
        assertThat(byId).containsKey("Acme.Repository.Find(int id)");
        assertThat(byId.get("Acme.Repository.Find(int id)").getParent()).isEqualTo("Acme.Repository");
    }

    @Test
    void crlfFile_chunksWithoutCarriageReturns() {
        List<PxChunk> chunks = chunkFixture("CrlfClass.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.CrlfClass");
        assertThat(byId).containsKey("Acme.CrlfClass.Value");
        assertThat(byId).containsKey("Acme.CrlfClass.Get()");
        assertThat(byId.get("Acme.CrlfClass.Value").getContent()).doesNotContain("\r");
        assertThat(byId.get("Acme.CrlfClass.Get()").getContent()).doesNotContain("\r");
    }

    // ------------------------------------------------------------------
    // C# 8-14 modern syntax (must chunk without crashing)
    // ------------------------------------------------------------------

    @Test
    void fileScopedNamespace_producesChunksWithDottedFqn() {
        List<PxChunk> chunks = chunkFixture("FileScopedNamespace.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Modern.Widget");
        assertThat(byId.get("Acme.Modern.Widget").getParent()).isNull();
        assertThat(byId).containsKey("Acme.Modern.Widget.Value");
        assertThat(byId.get("Acme.Modern.Widget.Value").getParent()).isEqualTo("Acme.Modern.Widget");
        assertThat(byId).containsKey("Acme.Modern.Widget.usings");
    }

    @Test
    void union_producesTypeFrameAndFieldChunks() {
        List<PxChunk> chunks = chunkFixture("Union.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Shape");
        assertThat(byId.get("Acme.Shape").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.TYPE_FRAME.getName());
        assertThat(byId).containsKey("Acme.Shape.I");
        assertThat(byId).containsKey("Acme.Shape.S");
        assertThat(byId.get("Acme.Shape.I").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.FIELD.getName());
    }

    @Test
    void delegate_producesTypeFrameAndDoc() {
        List<PxChunk> chunks = chunkFixture("Delegate.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Calculator");
        assertThat(byId.get("Acme.Calculator").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.TYPE_FRAME.getName());
        assertThat(byId).containsKey("Acme.Calculator.doc");
        assertThat(byId.get("Acme.Calculator.doc").getContent()).contains("A delegate that adds.");
    }

    @Test
    void eventWithAccessors_producesEventChunk() {
        List<PxChunk> chunks = chunkFixture("EventAccessors.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Publisher");
        assertThat(byId).containsKey("Acme.Publisher.Changed");
        assertThat(byId.get("Acme.Publisher.Changed").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.EVENT.getName());
        assertThat(byId.get("Acme.Publisher.Changed").getParent()).isEqualTo("Acme.Publisher");
    }

    @Test
    void recordWithBody_producesTypeFrameAndMethodChunks() {
        List<PxChunk> chunks = chunkFixture("RecordWithBody.cs");
        Map<String, PxChunk> byId = byId(chunks);

        assertThat(byId).containsKey("Acme.Point");
        assertThat(byId.get("Acme.Point").getMetadata().get(CSharpCodeSection.METADATA_KEY))
                .isEqualTo(CSharpCodeSection.TYPE_FRAME.getName());
        assertThat(byId).containsKey("Acme.Point.Sum()");
        assertThat(byId.get("Acme.Point.Sum()").getParent()).isEqualTo("Acme.Point");
    }

    // ------------------------------------------------------------------
    // Edge cases / fallback
    // ------------------------------------------------------------------

    @Test
    void blankFile_producesNoChunks() throws IOException {
        Path f = tempFile("Blank.cs", "");
        assertThat(chunker.chunk(f.toFile())).isEmpty();
    }

    @Test
    void unreadableFile_producesNoChunks() {
        // A path that does not exist: Files.readString throws (an IOException),
        // so the chunker must fall back to an empty stream rather than crash.
        Path f = tempDir.resolve("DoesNotExist.cs");
        assertThat(chunker.chunk(f.toFile())).isEmpty();
    }

    @Test
    void usingsOnlyFile_usesFileNameForUsingsId() throws IOException {
        Path f = tempFile("OrphanUsings.cs", "using System;\nusing System.IO;\n");
        List<PxChunk> chunks = chunker.chunk(f.toFile()).collect(Collectors.toList());

        List<PxChunk> usings = sections(chunks, CSharpCodeSection.USINGS);
        assertThat(usings).hasSize(1);
        assertThat(usings.get(0).getId()).isEqualTo("OrphanUsings.cs.usings");
        assertThat(usings.get(0).getContent()).contains("using System;");
    }

    // ------------------------------------------------------------------
    // matches()
    // ------------------------------------------------------------------

    @Test
    void matches_onlyCsFiles() {
        assertThat(chunker.matches(new File("Foo.cs"))).isTrue();
        assertThat(chunker.matches(new File("Foo.java"))).isFalse();
        assertThat(chunker.matches(new File("Foo.CS"))).isFalse(); // case-sensitive, like the Java chunker
    }
}
