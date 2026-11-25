package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.ReaderSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 05: the {@link CSharpFileViewProvider} must splice the full member bodies
 * (and docs, enum constants, usings) into the type frames, so that {@code readFile}
 * returns the complete file — the same quality the Java provider delivers.
 */
class CSharpFileViewProviderTest {

    private final CSharpCodeChunker chunker = new CSharpCodeChunker();
    private final CSharpFileViewProvider provider = new CSharpFileViewProvider();

    @TempDir
    Path tempDir;

    /** Writes {@code content} to a new {@code .cs} file, chunks it, combines the units and renders the view. */
    private String renderFile(String name, String content) throws IOException {
        Path f = tempDir.resolve(name);
        Files.writeString(f, content);
        List<PxChunk> chunks = chunker.chunk(f.toFile()).toList();
        List<PxChunk> units = ReaderSupport.combineUnits(chunks);
        return provider.render(units);
    }

    @Test
    void classFile_rendersFullMethodBodiesSplicedIntoFrame() throws IOException {
        String source = """
                using System;
                using System.Collections.Generic;

                namespace BBCore.Model;

                /// <summary>
                /// The event bus.
                /// </summary>
                public class EventBus {
                    private readonly List<string> listeners = new();

                    public bool Canceled { get; set; }

                    /// <summary>
                    /// Fires the event.
                    /// </summary>
                    public void Dispatch(string engine, string e) {
                        FireEventWithCancel(engine, e);
                        if (Canceled) return;
                        InvokeMutation(engine);
                    }

                    public void Continue(string engine) {
                        Resume(engine);
                    }
                }
                """;

        String view = renderFile("EventBus.cs", source);

        // full method bodies are present (not the collapsed skeleton)
        assertThat(view).contains("FireEventWithCancel(engine, e);");
        assertThat(view).contains("if (Canceled) return;");
        assertThat(view).contains("InvokeMutation(engine);");
        assertThat(view).contains("Resume(engine);");
        // the property and field are present
        assertThat(view).contains("public bool Canceled { get; set; }");
        assertThat(view).contains("private readonly List<string> listeners = new();");
        // usings are prepended to the frame
        assertThat(view).contains("using System;");
        assertThat(view).contains("using System.Collections.Generic;");
        // the type doc is spliced above the declaration
        assertThat(view).contains("The event bus.");
        // the method doc is spliced above the signature
        assertThat(view).contains("Fires the event.");
        // the frame header carries the FQN
        assertThat(view).contains("## BBCore.Model.EventBus");
        // the skeleton signature line was replaced, not duplicated:
        // the body line must appear exactly once
        assertThat(view).contains("public void Dispatch(string engine, string e) {");
        assertThat(countOccurrences(view, "FireEventWithCancel(engine, e);")).isEqualTo(1);
    }

    @Test
    void enumFile_rendersFullConstantsBlock() throws IOException {
        String source = """
                namespace BBCore.Model;

                public enum MutationType {
                    Dummy,
                    Move,
                    Attack
                }
                """;

        String view = renderFile("MutationType.cs", source);

        assertThat(view).contains("## BBCore.Model.MutationType");
        assertThat(view).contains("Dummy,");
        assertThat(view).contains("Move,");
        assertThat(view).contains("Attack");
        // the constants block appears exactly once (frame constant lines replaced, not duplicated)
        assertThat(countOccurrences(view, "Move,")).isEqualTo(1);
    }

    @Test
    void multiTypeFile_rendersEachTypeAsOwnBlock() throws IOException {
        String source = """
                namespace Acme;

                public class Outer {
                    public int Add(int a, int b) {
                        return a + b;
                    }

                    public class Inner {
                        public int Double(int x) {
                            return x * 2;
                        }
                    }
                }

                public interface IShape {
                    double Area();
                }
                """;

        String view = renderFile("Multi.cs", source);

        assertThat(view).contains("## Acme.Outer");
        assertThat(view).contains("## Acme.Outer.Inner");
        assertThat(view).contains("## Acme.IShape");
        // nested type body spliced into its own frame
        assertThat(view).contains("return x * 2;");
        // outer method body spliced into the outer frame
        assertThat(view).contains("return a + b;");
    }

    @Test
    void attributeAnnotatedMember_splicesWithAttributes() throws IOException {
        String source = """
                namespace Acme;

                public class Widget {
                    [Obsolete("use Add")]
                    public int Legacy(int a) {
                        return a + 1;
                    }
                }
                """;

        String view = renderFile("Widget.cs", source);

        assertThat(view).contains("[Obsolete(\"use Add\")]");
        assertThat(view).contains("return a + 1;");
        // the attribute line appears exactly once (frame line replaced, not duplicated)
        assertThat(countOccurrences(view, "[Obsolete(\"use Add\")]")).isEqualTo(1);
    }

    @Test
    void rawFileFallback_rendersConcatenatedParts() throws IOException {
        // a parse error forces the rawFile fallback (no frames)
        String source = """
                namespace Acme;

                public class Broken {
                    public void M( {
                """;

        String view = renderFile("Broken.cs", source);

        assertThat(view).contains("public class Broken {");
        assertThat(view).contains("public void M( {");
    }

    @Test
    void orphanMember_missingParentFrame_rendersAsOwnBlock() {
        // a frame that exists (Acme.Widget) plus a member whose parent frame (Acme.Gadget) is missing
        PxChunk frame = PxChunk.create(
                c -> c.setMimeType(CSharpCodeChunker.CSHARP_CODE_MIME_TYPE),
                c -> c.setId("Acme.Widget"),
                c -> c.setFile("/tmp/Widget.cs"),
                c -> c.setContent("namespace Acme {\npublic class Widget{\n    public int Sub(int a) {\n}\n}"),
                c -> c.setFromLine("3"),
                c -> c.setToLine("7"),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.TYPE_FRAME.getName())
        );
        PxChunk orphan = PxChunk.create(
                c -> c.setMimeType(CSharpCodeChunker.CSHARP_CODE_MIME_TYPE),
                c -> c.setId("Acme.Gadget.Add(int a, int b)"),
                c -> c.setParent("Acme.Gadget"),
                c -> c.setFile("/tmp/Widget.cs"),
                c -> c.setContent("public int Add(int a, int b) {\n    return a + b;\n}"),
                c -> c.setFromLine("10"),
                c -> c.setToLine("13"),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.METHOD.getName())
        );

        String view = provider.render(List.of(frame, orphan));

        assertThat(view).contains("## Acme.Widget");
        assertThat(view).contains("## Acme.Gadget.Add(int a, int b)");
        assertThat(view).contains("return a + b;");
    }

    @Test
    void framelessFile_fallsBackToLineOrderedConcatenation() {
        // no frames at all (e.g. a member-only unit set): plain concatenation in line order
        PxChunk method = PxChunk.create(
                c -> c.setMimeType(CSharpCodeChunker.CSHARP_CODE_MIME_TYPE),
                c -> c.setId("Acme.Widget.Add(int a, int b)"),
                c -> c.setParent("Acme.Widget"),
                c -> c.setFile("/tmp/Widget.cs"),
                c -> c.setContent("public int Add(int a, int b) {\n    return a + b;\n}"),
                c -> c.setFromLine("10"),
                c -> c.setToLine("13"),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, CSharpCodeSection.METHOD.getName())
        );

        String view = provider.render(List.of(method));

        assertThat(view).contains("public int Add(int a, int b) {");
        assertThat(view).contains("return a + b;");
        assertThat(view).doesNotContain("## ");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
