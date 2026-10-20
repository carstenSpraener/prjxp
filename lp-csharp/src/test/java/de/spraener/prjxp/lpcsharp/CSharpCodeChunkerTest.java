package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CSharpCodeChunkerTest {

    private final CSharpCodeChunker chunker = new CSharpCodeChunker();

    private List<PxChunk> chunkFixture(String name) {
        File f = new File("src/test/resources/fixtures/" + name);
        assertThat(f).exists();
        return chunker.chunk(f).collect(Collectors.toList());
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
    void syntaxError_fallsBackToRawFile() {
        List<PxChunk> chunks = chunkFixture("SyntaxError.cs");

        assertThat(sections(chunks, CSharpCodeSection.RAW_FILE)).isNotEmpty();
        assertThat(sections(chunks, CSharpCodeSection.TYPE_FRAME)).isEmpty();
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
