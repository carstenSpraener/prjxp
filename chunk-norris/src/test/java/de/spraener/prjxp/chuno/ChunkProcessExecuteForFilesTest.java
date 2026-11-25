package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
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
                vetoRegistry, cfg, mock(TransferPasswordResolver.class),
                new LanguagePackRegistry(List.of(), List.of()));
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

    // ------------------------------------------------- ID consistency with the full reindex (handlePath)

    /**
     * The hub's live update deletes the re-embedded file's documents and re-adds these chunks, so
     * the IDs must match the full-reindex IDs: basename-based IDs are rebased onto the
     * project-relative path. Without this, same-named files in different directories (two
     * README.md) collide on the store identity (project, id, part) and the index ends up with
     * duplicates ("Duplicate semantic node id" during enrichment).
     */
    @Test
    void sameBasenameFilesInDifferentDirs_getUniquePathBasedIds() throws Exception {
        Path readme1 = Files.writeString(Files.createDirectories(root.resolve("dir1")).resolve("README.md"), "# one");
        Path readme2 = Files.writeString(Files.createDirectories(root.resolve("dir2")).resolve("README.md"), "# two");

        PxChunker chunker = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(readme1, readme2));

        assertThat(result).extracting(PxChunk::getId)
                .containsExactlyInAnyOrder("dir1/README.md", "dir2/README.md");
    }

    /** MarkdownChunker-style section IDs (basename + ":section") are rebased the same way. */
    @Test
    void sectionStyleIdsAreRebasedOntoProjectPath() throws Exception {
        Path readme = Files.writeString(Files.createDirectories(root.resolve("mcp-server")).resolve("README.md"), "# one");

        PxChunker chunker = mock(PxChunker.class);
        when(chunker.chunk(any(File.class))).thenAnswer(inv -> {
            File f = inv.getArgument(0);
            return Stream.of(chunk(f.getName() + ":01", f.getAbsolutePath()));
        });
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(readme));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("mcp-server/README.md:01");
    }

    /** FQN-style IDs (Java/TS chunkers) are not basename-based and must not be mangled. */
    @Test
    void nonBasenameIdsAreLeftUntouched() throws Exception {
        Path file = Files.writeString(root.resolve("Foo.java"), "class Foo {}");

        PxChunker chunker = mock(PxChunker.class);
        when(chunker.chunk(any(File.class))).thenAnswer(inv -> {
            File f = inv.getArgument(0);
            return Stream.of(chunk("com.example.Foo", f.getAbsolutePath()));
        });
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(chunker));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("com.example.Foo");
    }

    /**
     * FullTextChunker is gated per project exactly like in {@code handlePath}: with fulltext
     * disabled it must not run (the full reindex would not have created those documents, and for
     * code files its absolute-path ID collides with the code chunker's ID).
     */
    @Test
    void fulltextDisabled_excludesFullTextChunker() throws Exception {
        Path file = Files.writeString(root.resolve("a.md"), "some content");

        PxChunker fulltext = new FullTextChunker();
        PxChunker other = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(fulltext, other));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(file));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata().get(PxChunk.PXCHUNK_TYPE)).isNull();
    }

    @Test
    void fulltextEnabled_includesFullTextChunker() throws Exception {
        pd.setFulltext(true);
        Path file = Files.writeString(root.resolve("a.md"), "some content");

        PxChunker fulltext = new FullTextChunker();
        PxChunker other = chunkerReturningFileAsId();
        when(factory.createChunker(any(File.class))).thenAnswer(inv -> Stream.of(fulltext, other));
        when(vetoRegistry.shouldVeto(any(Path.class), any())).thenReturn(false);

        List<PxChunk> result = uut.executeForFiles(pd, List.of(file));

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(c ->
                assertThat(c.getMetadata().get(PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.FULLTEXT_MARKER));
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
