package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 02 (DockerHub): the import poller. Uses a REAL {@link TarExtractor}, a mocked
 * {@link ImportHandler} and a real registry + index; import/projects dirs come from a {@code @TempDir}.
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

    private LuceneEmbeddingStore luceneStore;
    private HubProjectRegistry registry;
    private PipelineOrchestrator orchestrator;   // mocked — enqueues are captured, no real pipeline

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

        Path storeDir = Files.createDirectories(tempDir.resolve("store"));
        luceneStore = new LuceneEmbeddingStore(storeDir, 8);

        registry = new HubProjectRegistry(new PrjXPConfig(), props, new PxChunkDaoProvider(List.of()),
                luceneStore, mock(EmbeddingModel.class), new ProjectConfigFileParser());

        orchestrator = mock(PipelineOrchestrator.class);

        poller = new ImportPoller(props, new TarExtractor(props), handlerProvider, registry, orchestrator);
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private void indexChunkFor(String project) {
        luceneStore.addAll(
                List.of(Embedding.from(new float[8])),
                List.of(TextSegment.from("content of " + project,
                        Metadata.from(Map.of(PxChunk.PXCHUNK_PROJECT, project)))));
    }

    private Path liveDir(String name) throws IOException {
        return Files.createDirectories(importDir.resolve(name));
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

    // ------------------------------------------------------------------ tests (tar branch — Phase 02 regression)

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

    // ------------------------------------------------------------------ tests (live branch — Phase 06)

    @Test
    void liveDirWithYamlMarkerIsRegisteredAndEnqueued() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");

        poller.poll();

        ProjectEntry entry = registry.entry("foo").orElseThrow();
        assertThat(entry.getKind()).isEqualTo(ProjectEntry.Kind.LIVE);
        assertThat(entry.getStatus()).isEqualTo(ProjectStatus.IMPORTING);   // pipeline runs async (mocked)
        verify(orchestrator).enqueue("foo");
    }

    @Test
    void liveDirWithYmlMarkerIsRegisteredAndEnqueued() throws IOException {
        Path dir = liveDir("bar");
        Files.writeString(dir.resolve("prjxp.yml"), "name: bar\n");

        poller.poll();

        assertThat(registry.entry("bar").orElseThrow().getKind()).isEqualTo(ProjectEntry.Kind.LIVE);
        verify(orchestrator).enqueue("bar");
    }

    @Test
    void importDirEntryWithoutMarkerIsIgnored() throws IOException {
        liveDir("plain");   // no prjxp.yaml/yml inside

        poller.poll();

        assertThat(registry.availableProjects()).isEmpty();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rePollWithNoChangesIsIdempotent() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");

        poller.poll();
        poller.poll();   // marker still there, entry already known

        verify(orchestrator, times(1)).enqueue("foo");   // never re-enqueued
    }

    @Test
    void failedLiveProjectIsNotReEnqueued() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");

        poller.poll();
        registry.setStatus("foo", ProjectStatus.FAILED, "embed blew up");

        poller.poll();   // FAILED must not be re-enqueued — only an explicit reindex restarts it

        verify(orchestrator, times(1)).enqueue("foo");
    }

    @Test
    void disappearedLiveProjectIsDeregisteredAndIndexWiped() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");
        poller.poll();
        indexChunkFor("foo");

        Files.delete(dir.resolve("prjxp.yaml"));   // marker removed (directory stays)

        poller.poll();

        assertThat(registry.entry("foo")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();   // scoped wipe
    }

    @Test
    void vanishedLiveDirectoryIsDeregisteredAndIndexWiped() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");
        poller.poll();
        indexChunkFor("foo");

        Files.delete(dir.resolve("prjxp.yaml"));
        Files.delete(dir);   // the whole directory vanishes

        poller.poll();

        assertThat(registry.entry("foo")).isEmpty();
        assertThat(luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, "foo"))).isFalse();
    }

    @Test
    void missingImportDirSkipsLiveSyncAndKeepsRegisteredProjects() throws IOException {
        Path dir = liveDir("foo");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: foo\n");
        poller.poll();

        Files.delete(dir.resolve("prjxp.yaml"));
        Files.delete(dir);
        Files.delete(importDir);   // volume glitch: the import dir itself is gone

        assertThatCode(() -> poller.poll()).doesNotThrowAnyException();
        assertThat(registry.entry("foo")).isPresent();   // NOT deregistered — no wipe on a glitch
    }

    @Test
    void tarAndLiveImportsCoexistInOnePoll() throws IOException {
        Path tar = importDir.resolve("snap.tar");
        writeTar(tar, false, out -> writeEntry(out, "src/A.java", "class A {}"));
        Path live = liveDir("foo");
        Files.writeString(live.resolve("prjxp.yaml"), "name: foo\n");

        poller.poll();

        verify(handler).onImported(eq("snap"), eq(projectsRoot.resolve("snap")));   // tar branch unchanged
        assertThat(registry.entry("foo").orElseThrow().getKind()).isEqualTo(ProjectEntry.Kind.LIVE);
        verify(orchestrator).enqueue("foo");   // live branch enqueues; tar goes through the handler
    }
}
