package de.spraener.prjxp.mcp.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocsServiceTest {

    @TempDir
    Path tempDir;

    private DocsService service() {
        return new DocsService(tempDir.toString());
    }

    @Test
    void buildTreeNestedStructure() throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.writeString(tempDir.resolve("a/one.md"), "# One\n");
        Files.writeString(tempDir.resolve("a/b/two.md"), "no heading\n");
        Files.writeString(tempDir.resolve("z.md"), "no heading\n");
        Files.writeString(tempDir.resolve("a/notes.txt"), "plain text\n");
        Files.writeString(tempDir.resolve(".hidden.md"), "# Hidden\n");
        Files.createDirectories(tempDir.resolve(".hiddendir"));
        Files.writeString(tempDir.resolve(".hiddendir/x.md"), "# X\n");

        DocsNode root = service().buildTree();

        assertThat(root.name()).isEqualTo(tempDir.getFileName().toString());
        assertThat(root.type()).isEqualTo("root");
        assertThat(root.children()).hasSize(2);

        DocsNode folderA = root.children().get(0);
        assertThat(folderA.type()).isEqualTo("folder");
        assertThat(folderA.name()).isEqualTo("a");
        assertThat(folderA.children()).hasSize(2);
        assertThat(folderA.children().get(0).type()).isEqualTo("folder");
        assertThat(folderA.children().get(0).name()).isEqualTo("b");
        assertThat(folderA.children().get(1).type()).isEqualTo("doc");
        assertThat(folderA.children().get(1).name()).isEqualTo("One");
        assertThat(folderA.children().get(1).path()).isEqualTo("a/one.md");

        DocsNode docZ = root.children().get(1);
        assertThat(docZ.type()).isEqualTo("doc");
        assertThat(docZ.name()).isEqualTo("z");
        assertThat(docZ.path()).isEqualTo("z.md");
    }

    @Test
    void docNameFromH1() throws Exception {
        Files.writeString(tempDir.resolve("with-h1.md"), "# My Title\n\nbody\n");
        Files.writeString(tempDir.resolve("plain.md"), "no heading here\n");

        DocsNode root = service().buildTree();
        List<DocsNode> docs = root.children();

        assertThat(docs).extracting(DocsNode::name).containsExactly("My Title", "plain");
    }

    @Test
    void chaptersFromH2() throws Exception {
        String md = String.join("\n",
                "# T",
                "## Alpha",
                "### Beta",
                "## Gamma",
                "##no-space",
                "```",
                "## Hidden",
                "```");
        Files.writeString(tempDir.resolve("chapters.md"), md);

        DocsNode root = service().buildTree();
        DocsNode doc = root.children().get(0);

        assertThat(doc.chapters())
                .extracting(DocsChapter::title)
                .containsExactly("Alpha", "Gamma");
        assertThat(doc.chapters())
                .extracting(DocsChapter::anchor)
                .containsExactly("alpha", "gamma");
    }

    @Test
    void slugDedup() throws Exception {
        String md = String.join("\n",
                "# T",
                "## Setup",
                "## Setup");
        Files.writeString(tempDir.resolve("dedup.md"), md);

        DocsNode root = service().buildTree();
        DocsNode doc = root.children().get(0);

        assertThat(doc.chapters())
                .extracting(DocsChapter::anchor)
                .containsExactly("setup", "setup-2");
    }

    @Test
    void missingDirReturnsEmptyRoot() {
        DocsNode root = new DocsService(tempDir.resolve("nope").toString()).buildTree();

        assertThat(root.type()).isEqualTo("root");
        assertThat(root.name()).isEqualTo("nope");
        assertThat(root.children()).isEmpty();
    }

    @Test
    void readContent() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "# Hello\n\ncontent\n");

        assertThat(service().readContent("a.md")).hasValue("# Hello\n\ncontent\n");
        assertThat(service().readContent("../x.md")).isEmpty();
        assertThat(service().readContent("/etc/passwd")).isEmpty();
        assertThat(service().readContent("missing.md")).isEmpty();
    }

    @Test
    void readAsset() throws Exception {
        Files.write(tempDir.resolve("a.png"), new byte[]{1, 2, 3});
        Files.writeString(tempDir.resolve("x.unknownext"), "data");

        Optional<DocsService.DocsAsset> png = service().readAsset("a.png");
        assertThat(png).isPresent();
        assertThat(png.get().data()).containsExactly(1, 2, 3);
        assertThat(png.get().contentType()).isEqualTo("image/png");

        assertThat(service().readAsset("x.unknownext").get().contentType()).isEqualTo("application/octet-stream");
        assertThat(service().readAsset("../a.png")).isEmpty();
    }
}
