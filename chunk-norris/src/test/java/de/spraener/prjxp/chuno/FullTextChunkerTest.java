package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FullTextChunkerTest {

    @TempDir
    Path tempDir;

    private final FullTextChunker uut = new FullTextChunker();

    @Test
    void smallFileYieldsSinglePartWithExactContent() throws Exception {
        String content = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n"));
        File f = tempDir.resolve("small.txt").toFile();
        Files.writeString(f.toPath(), content);

        List<PxChunk> parts = uut.chunk(f).toList();

        assertThat(parts).hasSize(1);
        PxChunk part = parts.get(0);
        assertThat(part.getMimeType()).isEqualTo(FullTextChunker.MIME);
        assertThat(part.getMetadata().get(PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.FULLTEXT_MARKER);
        assertThat(part.getId()).isEqualTo(f.getAbsolutePath());
        assertThat(part.getFile()).isEqualTo(f.getAbsolutePath());
        assertThat(part.getOverlap()).isZero();
        assertThat(part.getPart()).isZero();
        assertThat(part.getTotal()).isEqualTo(1);
        assertThat(PxChunk.combine(new ArrayList<>(parts)).getContent()).isEqualTo(content);
    }

    @Test
    void largeFileYieldsMultipleParts() throws Exception {
        String content = "x".repeat(20_000) + "y".repeat(20_000);
        File f = tempDir.resolve("large.txt").toFile();
        Files.writeString(f.toPath(), content);

        List<PxChunk> parts = uut.chunk(f).toList();

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).getPart()).isZero();
        assertThat(parts.get(1).getPart()).isEqualTo(1);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.getTotal()).isEqualTo(2);
            assertThat(p.getOverlap()).isZero();
            assertThat(p.getMetadata().get(PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.FULLTEXT_MARKER);
        });
        assertThat(PxChunk.combine(new ArrayList<>(parts)).getContent()).isEqualTo(content);
    }

    @Test
    void binaryFileYieldsNoChunks() throws Exception {
        File f = tempDir.resolve("bin.dat").toFile();
        Files.write(f.toPath(), new byte[]{'a', 0, 'b'});

        assertThat(uut.chunk(f)).isEmpty();
    }

    @Test
    void emptyFileYieldsNoChunks() throws Exception {
        File f = tempDir.resolve("empty.txt").toFile();
        Files.writeString(f.toPath(), "");

        assertThat(uut.chunk(f)).isEmpty();
    }

    @Test
    void unreadableFileYieldsNoChunksWithoutException() throws Exception {
        // A directory reliably triggers an IOException in Files.readString on every platform/user.
        Path dir = Files.createDirectory(tempDir.resolve("adir"));

        assertThat(uut.chunk(dir.toFile())).isEmpty();
    }
}
