package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GdscriptCodeChunkerTest {

    private final GdscriptCodeChunker chunker = new GdscriptCodeChunker();

    private File fixture(String name) {
        return Paths.get("src", "test", "resources", "fixtures", name).toFile();
    }

    private List<PxChunk> chunk(String name) {
        File f = fixture(name);
        assertThat(f).as("fixture %s", name).exists();
        return chunker.chunk(f).collect(Collectors.toList());
    }

    private static List<String> ids(List<PxChunk> chunks) {
        return chunks.stream().map(PxChunk::getId).collect(Collectors.toList());
    }

    private static String section(PxChunk c) {
        return c.getMetadata().get(GdscriptCodeSection.METADATA_KEY);
    }

    @Test
    void sectionFromNameRoundTrips() {
        for (GdscriptCodeSection s : GdscriptCodeSection.values()) {
            assertThat(GdscriptCodeSection.fromName(s.getName())).isEqualTo(s);
        }
        assertThat(GdscriptCodeSection.fromName("nope")).isEqualTo(GdscriptCodeSection.UNKNOWN);
    }

    @Test
    void matchesGdFilesOnly() {
        assertThat(chunker.matches(fixture("Player.gd"))).isTrue();
        assertThat(chunker.matches(new File("Player.java"))).isFalse();
        assertThat(chunker.matches(new File("Player.gd.txt"))).isFalse();
    }

    @Test
    void playerExpectedSections() {
        assertThat(ids(chunk("Player.gd"))).containsExactly(
                "Player", "Player.fields", "Player.move(dir).doc", "Player.move(dir)",
                "Player._ready()", "Player.AI", "Player.AI.fields");
    }

    @Test
    void playerParentsAndSections() {
        List<PxChunk> c = chunk("Player.gd");
        assertThat(c.get(0).getParent()).isNull();
        assertThat(section(c.get(0))).isEqualTo("header");
        assertThat(c.get(1).getParent()).isEqualTo("Player");
        assertThat(section(c.get(1))).isEqualTo("fields");
        assertThat(c.get(2).getParent()).isEqualTo("Player.move(dir)");
        assertThat(section(c.get(2))).isEqualTo("funcDoc");
        assertThat(c.get(3).getParent()).isEqualTo("Player");
        assertThat(section(c.get(3))).isEqualTo("func");
        assertThat(c.get(4).getParent()).isEqualTo("Player");
        assertThat(section(c.get(4))).isEqualTo("func");
        assertThat(c.get(5).getParent()).isEqualTo("Player");
        assertThat(section(c.get(5))).isEqualTo("header");
        assertThat(c.get(6).getParent()).isEqualTo("Player.AI");
        assertThat(section(c.get(6))).isEqualTo("fields");
    }

    @Test
    void noClassNameExpectedSections() {
        assertThat(ids(chunk("NoClassName.gd"))).containsExactly(
                "NoClassName.gd", "NoClassName.gd.fields", "NoClassName.gd.go()");
    }

    @Test
    void multiLineExpectedSections() {
        assertThat(ids(chunk("MultiLine.gd"))).containsExactly(
                "Widget", "Widget.fields", "Widget.do_thing(a, b = 2)");
    }

    @Test
    void multiLineFieldsCoversEnumAndActive() {
        PxChunk fields = chunk("MultiLine.gd").get(1);
        assertThat(section(fields)).isEqualTo("fields");
        assertThat(fields.getContent()).contains("enum Mode {");
        assertThat(fields.getContent()).contains("var active = true:");
    }

    @Test
    void losslessTiling() throws Exception {
        for (String name : List.of("Player.gd", "NoClassName.gd", "MultiLine.gd")) {
            String fileText = Files.readString(fixture(name).toPath(), StandardCharsets.UTF_8);
            String rejoined = chunk(name).stream()
                    .map(PxChunk::getContent)
                    .collect(Collectors.joining());
            assertThat(rejoined).as(name).isEqualTo(fileText);
        }
    }

    @Test
    void symbolMetadata() {
        List<PxChunk> c = chunk("Player.gd");
        assertThat(c.get(0).getMetadata().get("symbol_fqn")).isEqualTo("Player");
        assertThat(c.get(0).getMetadata().get("symbol_type")).isEqualTo("header");
        assertThat(c.get(3).getMetadata().get("symbol_name")).isEqualTo("move");
        assertThat(c.get(3).getMetadata().get("symbol_type")).isEqualTo("func");
        assertThat(c.get(3).getMetadata().get("symbol_container_fqn")).isEqualTo("Player");
    }

    @Test
    void embeddingPrefixCarriesClassName() {
        List<PxChunk> c = chunk("Player.gd");
        assertThat(c.get(0).getEmbeddingPrefix()).isEqualTo("Player");
        assertThat(c.get(3).getEmbeddingPrefix()).isEqualTo("Player");
        // inner-class member carries the inner class name
        assertThat(c.get(6).getEmbeddingPrefix()).isEqualTo("AI");
    }

    @Test
    void splitsLargeSectionsAndRejoins() throws Exception {
        GdscriptCodeChunker small = new GdscriptCodeChunker(30, 0);
        File f = fixture("Player.gd");
        String fileText = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        List<PxChunk> chunks = small.chunk(f).collect(Collectors.toList());

        // splitting actually happens
        assertThat(chunks).as("some section must split").anySatisfy(c -> assertThat(c.getTotal()).isGreaterThan(1));

        // rejoin: group parts by id (insertion order = section order), combine, concat
        Map<String, List<PxChunk>> byId = new LinkedHashMap<>();
        for (PxChunk c : chunks) {
            byId.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(c);
        }
        StringBuilder sb = new StringBuilder();
        for (List<PxChunk> parts : byId.values()) {
            sb.append(PxChunk.combine(parts).getContent());
        }
        assertThat(sb.toString()).isEqualTo(fileText);
    }

    @Test
    void innerClassWithPreamble(@TempDir Path tempDir) throws Exception {
        // An inner class that has its own preamble (extends) exercises the
        // "recursion emitted a HEADER" branch of the inner-class expansion.
        File f = tempDir.resolve("Outer.gd").toFile();
        Files.writeString(f.toPath(),
                "class_name Outer\nclass Inner:\n\textends Node\n\tvar x = 1\n", StandardCharsets.UTF_8);
        List<PxChunk> chunks = chunker.chunk(f).collect(Collectors.toList());
        assertThat(ids(chunks)).containsExactly("Outer", "Outer.Inner", "Outer.Inner.fields");
        // the inner header reads `class Inner:` followed by its preamble
        assertThat(chunks.get(1).getContent()).isEqualTo("class Inner:\n\textends Node\n");
        assertThat(chunks.get(1).getParent()).isEqualTo("Outer");
        assertThat(chunks.get(2).getParent()).isEqualTo("Outer.Inner");
    }

    @Test
    void emptyFileFallsBackToRawFile(@TempDir Path tempDir) throws Exception {
        File f = tempDir.resolve("Empty.gd").toFile();
        Files.writeString(f.toPath(), "", StandardCharsets.UTF_8);
        List<PxChunk> chunks = chunker.chunk(f).collect(Collectors.toList());
        // whatever parts the splitter emits, they are rawFile parts of the whole file
        for (PxChunk c : chunks) {
            assertThat(c.getId()).isEqualTo(f.getAbsolutePath());
            assertThat(section(c)).isEqualTo("rawFile");
        }
        String rejoined = chunks.stream().map(PxChunk::getContent).collect(Collectors.joining());
        assertThat(rejoined).isEqualTo("");
    }
}
