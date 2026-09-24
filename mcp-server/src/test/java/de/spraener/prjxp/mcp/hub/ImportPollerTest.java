package de.spraener.prjxp.mcp.hub;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 02 (DockerHub): the import poller. Uses a REAL {@link TarExtractor} and a mocked
 * {@link ImportHandler}; import/projects dirs come from a {@code @TempDir}.
 */
class ImportPollerTest {

    @TempDir
    Path tempDir;

    private Path importDir;
    private Path projectsRoot;
    private HubProperties props;
    private ImportHandler handler;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ImportHandler> handlerProvider = mock(ObjectProvider.class);

    private ImportPoller poller;

    @BeforeEach
    void setUp() throws IOException {
        importDir = Files.createDirectories(tempDir.resolve("import"));
        projectsRoot = Files.createDirectories(tempDir.resolve("projects"));

        props = new HubProperties();
        props.setImportDir(importDir.toString());
        props.setProjectsRoot(projectsRoot.toString());

        handler = mock(ImportHandler.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ImportHandler> consumer = invocation.getArgument(0);
            consumer.accept(handler);
            return null;
        }).when(handlerProvider).ifAvailable(any());

        poller = new ImportPoller(props, new TarExtractor(props), handlerProvider);
    }

    // ------------------------------------------------------------------ tar building helpers (same as TarExtractorTest)

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

    private void writeTar(Path tarFile, boolean gzip, TarWriter writer) throws IOException {
        OutputStream fileOut = Files.newOutputStream(tarFile);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                gzip ? new GzipCompressorOutputStream(fileOut) : fileOut)) {
            writer.write(tar);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validTarIsExtractedHandlerNotifiedAndTarDeleted() throws IOException {
        Path tar = importDir.resolve("myproj.tar");
        writeTar(tar, false, out -> writeEntry(out, "src/A.java", "class A {}"));

        poller.poll();

        verify(handler).onImported(eq("myproj"), eq(projectsRoot.resolve("myproj")));
        assertThat(tar).doesNotExist();
        assertThat(projectsRoot.resolve("myproj/A.java")).hasContent("class A {}"); // "src" stripped
    }

    @Test
    void invalidTarIsQuarantinedAndHandlerNotifiedOfFailure() throws IOException {
        Path tar = importDir.resolve("evil.tar");
        writeTar(tar, false, out -> writeEntry(out, "../evil.txt", "pwned"));

        poller.poll();

        verify(handler).onFailed(eq("evil"), anyString());
        assertThat(tar).doesNotExist(); // original gone
        assertThat(importDir.resolve("evil.tar.failed")).exists(); // renamed to .failed
    }

    @Test
    void failedFilesAreIgnoredOnNextPoll() throws IOException {
        Files.writeString(importDir.resolve("old.tar.failed"), "quarantined");

        assertThatCode(() -> poller.poll()).doesNotThrowAnyException();

        verifyNoInteractions(handler);
        assertThat(importDir.resolve("old.tar.failed")).exists(); // untouched
    }

    @Test
    void nonTarFilesAreIgnored() throws IOException {
        Files.writeString(importDir.resolve("notes.txt"), "hello");

        poller.poll();

        verifyNoInteractions(handler);
        assertThat(importDir.resolve("notes.txt")).exists(); // untouched
    }

    @Test
    void missingImportDirectoryIsIgnored() {
        props.setImportDir(tempDir.resolve("does-not-exist").toString());

        assertThatCode(() -> poller.poll()).doesNotThrowAnyException();
    }

    @Test
    void failingTarDoesNotBreakOtherImports() throws IOException {
        Path bad = importDir.resolve("a.tar");
        writeTar(bad, false, out -> writeEntry(out, "../evil.txt", "pwned"));
        Path good = importDir.resolve("b.tgz");
        writeTar(good, true, out -> writeEntry(out, "src/A.java", "class A {}"));

        poller.poll();

        verify(handler).onFailed(eq("a"), anyString());
        verify(handler).onImported(eq("b"), eq(projectsRoot.resolve("b")));
        assertThat(importDir.resolve("a.tar.failed")).exists();
        assertThat(good).doesNotExist();
    }

    @Test
    void tarGzExtensionIsStrippedForProjectName() throws IOException {
        Path tar = importDir.resolve("c.tar.gz");
        writeTar(tar, true, out -> writeEntry(out, "src/A.java", "class A {}"));

        poller.poll();

        verify(handler).onImported(eq("c"), eq(projectsRoot.resolve("c")));
        assertThat(tar).doesNotExist();
    }
}
