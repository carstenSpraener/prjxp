package de.spraener.prjxp.mcp.hub;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 02 (DockerHub): security-hardened tar extraction. Tars are built with commons-compress
 * into a {@code @TempDir}; no Spring context is involved.
 */
class TarExtractorTest {

    @TempDir
    Path tempDir;

    private static HubProperties props() {
        return new HubProperties(); // generous defaults: 2 GiB tar, 4 GiB extracted, 100k entries
    }

    // ------------------------------------------------------------------ tar building helpers

    @FunctionalInterface
    private interface TarWriter {
        void write(TarArchiveOutputStream out) throws IOException;
    }

    private static void writeEntry(TarArchiveOutputStream out, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length); // commons-compress 1.27: putArchiveEntry has no size overload
        out.putArchiveEntry(entry);
        out.write(bytes, 0, bytes.length);
        out.closeArchiveEntry();
    }

    private static void writeDirectory(TarArchiveOutputStream out, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
    }

    private static void writeSymlink(TarArchiveOutputStream out, String name, String linkTarget) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
        entry.setLinkName(linkTarget);
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
    }

    private static void writeAbsoluteEntry(TarArchiveOutputStream out, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        // preserveAbsolutePath=true: the default constructor normalizes away a leading "/"
        TarArchiveEntry entry = new TarArchiveEntry(name, true);
        entry.setSize(bytes.length);
        out.putArchiveEntry(entry);
        out.write(bytes, 0, bytes.length);
        out.closeArchiveEntry();
    }

    private static void writeTar(Path tarFile, boolean gzip, TarWriter writer) throws IOException {
        OutputStream fileOut = Files.newOutputStream(tarFile);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                gzip ? new GzipCompressorOutputStream(fileOut) : fileOut)) {
            writer.write(tar);
        }
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    void plainTarExtractsFilesWithContent() throws IOException {
        Path tar = tempDir.resolve("proj.tar");
        writeTar(tar, false, out -> {
            writeDirectory(out, "src/");
            writeEntry(out, "src/A.java", "class A {}");
            writeEntry(out, "src/B.ts", "const b = 1;");
        });

        Path target = tempDir.resolve("out/proj");
        Path result = new TarExtractor(props()).extract(tar, target);

        assertThat(result).isEqualTo(target.toAbsolutePath().normalize());
        // all non-directory entries share the single top-level dir "src" -> it is stripped
        assertThat(target.resolve("A.java")).hasContent("class A {}");
        assertThat(target.resolve("B.ts")).hasContent("const b = 1;");
    }

    @Test
    void gzippedTarExtractsSameContent() throws IOException {
        Path tar = tempDir.resolve("proj.tar.gz");
        writeTar(tar, true, out -> {
            writeDirectory(out, "src/");
            writeEntry(out, "src/A.java", "class A {}");
            writeEntry(out, "src/B.ts", "const b = 1;");
        });

        Path target = tempDir.resolve("out/proj-gz");
        new TarExtractor(props()).extract(tar, target);

        assertThat(target.resolve("A.java")).hasContent("class A {}");
        assertThat(target.resolve("B.ts")).hasContent("const b = 1;");
    }

    @Test
    void singleTopLevelDirectoryIsStripped() throws IOException {
        Path tar = tempDir.resolve("proj.tar");
        writeTar(tar, false, out -> writeEntry(out, "foo/src/A.java", "class A {}"));

        Path target = tempDir.resolve("out/proj");
        new TarExtractor(props()).extract(tar, target);

        assertThat(target.resolve("src/A.java")).hasContent("class A {}");
        assertThat(target.resolve("foo")).doesNotExist();
    }

    @Test
    void mixedTopLevelRootsAreExtractedAsIs() throws IOException {
        Path tar = tempDir.resolve("proj.tar");
        writeTar(tar, false, out -> {
            writeEntry(out, "foo/a.txt", "a");
            writeEntry(out, "bar/b.txt", "b");
        });

        Path target = tempDir.resolve("out/mixed");
        new TarExtractor(props()).extract(tar, target);

        assertThat(target.resolve("foo/a.txt")).hasContent("a");
        assertThat(target.resolve("bar/b.txt")).hasContent("b");
    }

    @Test
    void topLevelFilePreventsStripping() throws IOException {
        Path tar = tempDir.resolve("proj.tar");
        writeTar(tar, false, out -> {
            writeEntry(out, "src/A.java", "class A {}");
            writeEntry(out, "pom.xml", "<project/>");
        });

        Path target = tempDir.resolve("out/keep-roots");
        new TarExtractor(props()).extract(tar, target);

        assertThat(target.resolve("src/A.java")).hasContent("class A {}");
        assertThat(target.resolve("pom.xml")).hasContent("<project/>");
    }

    // ------------------------------------------------------------------ security violations

    @Test
    void zipSlipEntryIsRejectedAndNothingLandsOutsideTarget() throws IOException {
        Path tar = tempDir.resolve("evil.tar");
        writeTar(tar, false, out -> {
            writeEntry(out, "src/ok.txt", "fine");
            writeEntry(out, "../evil.txt", "pwned");
        });

        Path target = tempDir.resolve("out/evil");
        assertThatThrownBy(() -> new TarExtractor(props()).extract(tar, target))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("..");

        assertThat(tempDir.resolve("evil.txt")).doesNotExist(); // nothing outside the target
    }

    @Test
    void absolutePathEntryIsRejectedBeforeAnythingIsWritten() throws IOException {
        Path tar = tempDir.resolve("abs.tar");
        writeTar(tar, false, out -> writeAbsoluteEntry(out, "/etc/prjxp-evil", "pwned"));

        Path target = tempDir.resolve("out/abs");
        assertThatThrownBy(() -> new TarExtractor(props()).extract(tar, target))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("absolute");

        assertThat(target).doesNotExist(); // aborted in pass 1, before any write
    }

    @Test
    void symlinkEntryIsRejected() throws IOException {
        Path tar = tempDir.resolve("links.tar");
        writeTar(tar, false, out -> {
            writeEntry(out, "src/ok.txt", "fine");
            writeSymlink(out, "link", "/etc/passwd");
        });

        Path target = tempDir.resolve("out/links");
        assertThatThrownBy(() -> new TarExtractor(props()).extract(tar, target))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("link");

        assertThat(target).doesNotExist(); // aborted in pass 1, before any write
    }

    // ------------------------------------------------------------------ limits

    @Test
    void tarLargerThanMaxTarBytesIsRejected() throws IOException {
        Path tar = tempDir.resolve("big.tar");
        writeTar(tar, false, out -> writeEntry(out, "src/big.txt", "x".repeat(1024)));

        HubProperties props = props();
        props.setMaxTarBytes(10);

        assertThatThrownBy(() -> new TarExtractor(props).extract(tar, tempDir.resolve("out/big")))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("size limit");

        assertThat(tempDir.resolve("out/big")).doesNotExist();
    }

    @Test
    void extractedContentLargerThanMaxExtractedBytesIsRejected() throws IOException {
        Path tar = tempDir.resolve("bomb.tar");
        writeTar(tar, false, out -> writeEntry(out, "src/bomb.txt", "x".repeat(1024)));

        HubProperties props = props();
        props.setMaxExtractedBytes(10);

        Path target = tempDir.resolve("out/bomb");
        assertThatThrownBy(() -> new TarExtractor(props).extract(tar, target))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("size limit");

        assertThat(target.resolve("src/bomb.txt")).doesNotExist(); // no partial content
    }

    @Test
    void moreEntriesThanMaxEntriesIsRejected() throws IOException {
        Path tar = tempDir.resolve("many.tar");
        writeTar(tar, false, out -> {
            writeEntry(out, "a.txt", "1");
            writeEntry(out, "b.txt", "2");
            writeEntry(out, "c.txt", "3");
        });

        HubProperties props = props();
        props.setMaxEntries(2);

        assertThatThrownBy(() -> new TarExtractor(props).extract(tar, tempDir.resolve("out/many")))
                .isInstanceOf(TarSecurityException.class)
                .hasMessageContaining("entry limit");

        assertThat(tempDir.resolve("out/many")).doesNotExist();
    }

    // ------------------------------------------------------------------ re-import

    @Test
    void existingTargetDirectoryIsWipedBeforeReExtraction() throws IOException {
        Path target = tempDir.resolve("out/reimport");
        Files.createDirectories(target.resolve("src"));
        Files.writeString(target.resolve("stale.txt"), "old");

        Path tar = tempDir.resolve("reimport.tar");
        writeTar(tar, false, out -> writeEntry(out, "src/fresh.txt", "new"));

        new TarExtractor(props()).extract(tar, target);

        assertThat(target.resolve("stale.txt")).doesNotExist();
        assertThat(target.resolve("fresh.txt")).hasContent("new"); // "src" prefix stripped, stale src/ wiped
    }
}
