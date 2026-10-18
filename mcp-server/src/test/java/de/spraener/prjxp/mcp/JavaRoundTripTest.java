package de.spraener.prjxp.mcp;

import com.github.javaparser.StaticJavaParser;
import de.spraener.prjxp.chuno.code.java.JavaCodeChunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.reader.JavaFileViewProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip integration: a Java file is chunked with the real {@link JavaCodeChunker}
 * (chunk-norris) and restored with the real {@link JavaFileViewProvider} (golden-retriever).
 * mcp-server is the only module that depends on both, so the integration contract
 * (chunker output format ≙ provider splice expectations) is pinned here — a format
 * drift between the two modules would fail this test, which the unit tests on either
 * side (hand-crafted units / structural assertions) cannot catch.
 */
@SpringBootTest(properties = "prjxp.cli.enabled=false")
class JavaRoundTripTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void luceneIndex(DynamicPropertyRegistry registry) {
        registry.add("prjxp.embeddingStoreLucene.indexPath", () -> tempDir.resolve("lucene-index").toString());
    }

    @Autowired
    JavaCodeChunker chunker;

    @Autowired
    JavaFileViewProvider provider;

    @Test
    void parsedFileIsChunkedAndRestored() throws Exception {
        String code = """
                package de.spraener.test;

                import java.util.List;

                public class RoundTripClass {

                    private String name;
                    private int value;

                    /** Creates a default instance. */
                    public RoundTripClass() {
                        this.name = "default";
                        this.value = 0;
                    }

                    public RoundTripClass(String name) {
                        this.name = name;
                        this.value = 42;
                    }

                    /** Does the work. */
                    public void doWork() {
                        System.out.println(name);
                    }

                    public String getName() {
                        return name;
                    }

                    class Inner {
                        private int innerValue;

                        void innerWork() {
                            System.out.println(innerValue);
                        }
                    }

                    enum Mode {
                        FAST, SLOW;

                        private Mode() {}
                    }
                }
                """;
        Path file = tempDir.resolve("RoundTripClass.java");
        Files.writeString(file, code);

        List<PxChunk> units = chunker.chunk(file.toFile()).toList();
        String view = provider.render(units);

        // 1. Every non-frame unit's content is present in the restored view
        for (PxChunk unit : units) {
            if ("classFrame".equals(unit.getMetadata().get("java_code_section"))) {
                continue;
            }
            assertThat(view).as("content of unit %s", unit.getId()).contains(unit.getContent());
        }

        // 2. Every frame is rendered as its own block, in source order
        List<PxChunk> frames = units.stream()
                .filter(u -> "classFrame".equals(u.getMetadata().get("java_code_section")))
                .sorted(Comparator.comparingInt(u -> Integer.parseInt(u.getFromLine())))
                .toList();
        assertThat(frames).as("one frame per type (outer, inner, enum)").hasSize(3);
        int lastPos = -1;
        for (PxChunk frame : frames) {
            int pos = view.indexOf("## " + frame.getId() + "\n");
            assertThat(pos).as("frame block for %s", frame.getId()).isGreaterThan(lastPos);
            lastPos = pos;
        }

        // 3. No bare signature lines remain — every method/constructor was spliced into its body
        for (PxChunk unit : units) {
            String section = unit.getMetadata().get("java_code_section");
            if (!"method".equals(section) && !"constructor".equals(section)) {
                continue;
            }
            PxChunk frame = frames.stream()
                    .filter(f -> f.getId().equals(unit.getParent()))
                    .findFirst().orElseThrow();
            String sig = unit.getId().substring(frame.getId().length() + 1);
            assertThat(view.lines().map(String::strip).toList())
                    .as("bare signature line %s must have been replaced by the body", sig)
                    .doesNotContain(sig);
        }

        // 4. Every non-blank source line is present in the restored view
        List<String> viewLines = view.lines().map(String::strip).toList();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            assertThat(viewLines).as("source line %s", line.strip()).contains(line.strip());
        }

        // 5. Each restored frame block is valid Java again
        for (PxChunk frame : frames) {
            StaticJavaParser.parse(extractFrameBlock(view, frame.getId()));   // throws on syntax error
        }
    }

    @Test
    void unparseableFileIsRestoredByteExact() throws Exception {
        String code = """
                package de.spraener.test;

                public class Broken {

                    private String name;
                    private int value;

                    public Broken() {
                        this.name = "default";
                        this.value = 0;
                    }

                    public Broken(String name) {
                        this.name = name;
                        this.value = 42;
                    }

                    public void doWork() {
                        System.out.println(name);
                        System.out.println(value);
                    }

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }

                    public int getValue() {
                        return value;
                    }

                    public void setValue(int value) {
                        this.value = value;
                    }

                    // MISSING CLOSING BRACE — triggers ParseProblemException
                """;
        Path file = tempDir.resolve("Broken.java");
        Files.writeString(file, code);

        List<PxChunk> units = chunker.chunk(file.toFile()).toList();
        assertThat(units).isNotEmpty();
        assertThat(units).as("parse failure → raw fallback units")
                .allSatisfy(u -> assertThat(u.getMetadata().get("java_code_section")).isEqualTo("rawFile"));

        // The provider's frames.isEmpty() fallback concatenates the parts — byte-exact file text
        String view = provider.render(units);
        assertThat(view).isEqualTo(String.join("\n", Files.readAllLines(file)));
    }

    /** Extracts the ```java block of one frame from the rendered view. */
    private static String extractFrameBlock(String view, String frameId) {
        int start = view.indexOf("## " + frameId + "\n");
        assertThat(start).as("frame block for %s", frameId).isNotNegative();
        int codeStart = view.indexOf("```java\n", start) + "```java\n".length();
        int codeEnd = view.indexOf("\n```", codeStart);
        assertThat(codeEnd).as("closing fence for %s", frameId).isGreaterThan(codeStart);
        return view.substring(codeStart, codeEnd);
    }
}
