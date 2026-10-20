package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ChunkProcess#executeForFiles(ProjectDefinition, List)} — chunking an explicit
 * file list (no tree walk, no JSONL output) and returning the chunks in memory. Mirrors the
 * construction pattern of {@link ChunkProcessTest}.
 */
class ChunkProcessExecuteForFilesTest {

    @TempDir
    Path root;

    private ChunkerFactory factory;
    private VetoRegistry vetoRegistry;
    private PrjXPConfig cfg;
    private ChunkProcess uut;
    private ProjectDefinition pd;

    @BeforeEach
    void setUp() {
        factory = mock(ChunkerFactory.class);
        vetoRegistry = mock(VetoRegistry.class);
        cfg = new PrjXPConfig();
        uut = new ChunkProcess(mock(PxLogService.class), factory, mock(ApplicationEventPublisher.class),
                vetoRegistry, cfg, mock(TransferPasswordResolver.class));
        pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(root.toString());
        pd.setChunoWhiteList("md");
    }

    private PxChunk chunk(String id, String absFile) {
        return PxChunk.create(c -> c.setId(id), c -> c.setFile(absFile));
    }

    /** A chunker that emits one chunk whose id is the file's name and whose file is the absolute path. */
    private PxChunker chunkerReturningFileAsId() {
        PxChunker chunker = mock(PxChunker.class);
        when(chunker.chunk(any(File.class))).thenAnswer(inv -> {
            File f = inv.getArgument(0);
            return Stream.of(chunk(f.getName(), f.getAbsolutePath()));
        });
        return chunker;
    }

    @Test
    void returnsChunksForListedFiles() throws Exception {
        Path a = root.resolve("a.md");
        Path b = root.resolve("b.md");
        Files.writeString(a, "# A");
        Files.writeString(b, "# B");

        PxChunker chunker = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(a, b));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PxChunk::getId).containsExactlyInAnyOrder("a.md", "b.md");
    }

    @Test
    void mapsToProjectRelativePaths() throws Exception {
        Path nested = Files.createDirectories(root.resolve("docs").resolve("sub"));
        Path file = nested.resolve("x.md");
        Files.writeString(file, "# X");

        PxChunker chunker = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFile()).isEqualTo("docs/sub/x.md");
    }

    @Test
    void vetoedFileIsSkipped() throws Exception {
        Path keep = root.resolve("keep.md");
        Path veto = root.resolve("veto.md");
        Files.writeString(keep, "# Keep");
        Files.writeString(veto, "# Veto");

        PxChunker chunker = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenAnswer(inv ->
                ((Path) inv.getArgument(0)).getFileName().toString().equals("veto.md"));

        List<PxChunk> result = uut.executeForFiles(pd, List.of(keep, veto));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("keep.md");
    }

    @Test
    void nonRegularFileSkipped() throws Exception {
        Path dir = Files.createDirectories(root.resolve("adir"));
        Path file = root.resolve("a.md");
        Files.writeString(file, "# A");

        PxChunker chunker = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(dir, file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("a.md");
        verify(factory, never()).createChunker(dir.toFile());
    }

    @Test
    void emptyListReturnsEmpty() {
        assertThat(uut.executeForFiles(pd, List.of())).isEmpty();
    }

    @Test
    void noChunkerMatchReturnsEmpty() throws Exception {
        Path file = root.resolve("a.md");
        Files.writeString(file, "# A");

        when(factory.createChunker(any(File.class))).thenReturn(Stream.empty());
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        assertThat(uut.executeForFiles(pd, List.of(file))).isEmpty();
    }
}
