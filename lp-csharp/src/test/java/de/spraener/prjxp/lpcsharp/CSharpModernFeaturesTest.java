package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C# grammar upgrade Phase 03: one fixture per C# version 8-14, each
 * exercising that version's headline features. The key assertion is that the
 * Roslyn grammar + chunker parse modern syntax without falling back to
 * {@code rawFile}, and that the expected sections/FQNs are produced.
 */
class CSharpModernFeaturesTest {

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

    private static List<String> fqnOf(List<PxChunk> chunks, CSharpCodeSection section) {
        return sections(chunks, section).stream()
                .map(c -> c.getMetadata().get(SymbolMetadata.SYMBOL_FQN))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static void assertNoRawFile(List<PxChunk> chunks, String fixture) {
        assertThat(sections(chunks, CSharpCodeSection.RAW_FILE))
                .as("%s must not fall back to rawFile", fixture)
                .isEmpty();
    }

    @Test
    void csharp8_usingDeclarationRangeSwitchExpressionDefaultInterfaceMember() {
        List<PxChunk> chunks = chunkFixture("CSharp8.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp8.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp8.IShape", "Modern.CSharp8.Circle", "Modern.CSharp8.CSharp8Features");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp8.IShape#Perimeter",
                        "Modern.CSharp8.CSharp8Features#Classify",
                        "Modern.CSharp8.CSharp8Features#Slice",
                        "Modern.CSharp8.CSharp8Features#Log");
    }

    @Test
    void csharp9_positionalRecordInitAccessorWithExpression() {
        List<PxChunk> chunks = chunkFixture("CSharp9.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp9.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp9.Point", "Modern.CSharp9.Person");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp9.Person#WithName");
    }

    @Test
    void csharp10_fileScopedNamespaceGlobalUsing() {
        List<PxChunk> chunks = chunkFixture("CSharp10.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp10.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp10.Greeter");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp10.Greeter#Greet", "Modern.CSharp10.Greeter#GreetAll");
    }

    @Test
    void csharp11_rawStringGenericAttributeRequiredMember() {
        List<PxChunk> chunks = chunkFixture("CSharp11.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp11.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp11.Attr", "Modern.CSharp11.Config");
        assertThat(fqnOf(chunks, CSharpCodeSection.PROPERTY))
                .contains("Modern.CSharp11.Config#Host");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp11.Config#Template");

        // The #nullable directive flows 1:1 into the method chunk (line-range extraction).
        List<PxChunk> template = sections(chunks, CSharpCodeSection.METHOD).stream()
                .filter(c -> "Modern.CSharp11.Config#Template".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)))
                .collect(Collectors.toList());
        assertThat(template).hasSize(1);
        assertThat(template.get(0).getContent()).contains("#nullable enable");
    }

    @Test
    void csharp12_primaryConstructorCollectionExpressionGenericStatic() {
        List<PxChunk> chunks = chunkFixture("CSharp12.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp12.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp12.Dep", "Modern.CSharp12.Service", "Modern.CSharp12.Collections");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp12.Service#Describe",
                        "Modern.CSharp12.Collections#Make",
                        "Modern.CSharp12.Collections#Default");

        // The #region/#endregion directives flow 1:1 into the method chunk.
        List<PxChunk> make = sections(chunks, CSharpCodeSection.METHOD).stream()
                .filter(c -> "Modern.CSharp12.Collections#Make".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)))
                .collect(Collectors.toList());
        assertThat(make).hasSize(1);
        assertThat(make.get(0).getContent()).contains("#region values").contains("#endregion");
    }

    @Test
    void csharp13_fieldKeywordGenericParams() {
        List<PxChunk> chunks = chunkFixture("CSharp13.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp13.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp13.Widget");
        assertThat(fqnOf(chunks, CSharpCodeSection.PROPERTY))
                .contains("Modern.CSharp13.Widget#Name");
        assertThat(fqnOf(chunks, CSharpCodeSection.METHOD))
                .contains("Modern.CSharp13.Widget#First");
    }

    @Test
    void csharp14_unionExtensionBlockTopLevelStatements() {
        List<PxChunk> chunks = chunkFixture("CSharp14.cs");
        assertThat(chunks).isNotEmpty();
        assertNoRawFile(chunks, "CSharp14.cs");

        assertThat(fqnOf(chunks, CSharpCodeSection.TYPE_FRAME))
                .contains("Modern.CSharp14.Shape");
    }
}
