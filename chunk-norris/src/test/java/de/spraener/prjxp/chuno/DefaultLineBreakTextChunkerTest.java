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

class DefaultLineBreakTextChunkerTest {

    @TempDir
    Path tempDir;

    private final DefaultLineBreakTextChunker uut = new DefaultLineBreakTextChunker();

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
        assertThat(part.getMimeType()).isEqualTo(DefaultLineBreakTextChunker.MIME);
        assertThat(part.getMetadata().get(PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.TEXT_MARKER);
        assertThat(part.getId()).isEqualTo(f.getAbsolutePath() + ":text");
        assertThat(part.getFile()).isEqualTo(f.getAbsolutePath());
        assertThat(part.getOverlap()).isZero();
        assertThat(part.getPart()).isZero();
        assertThat(part.getTotal()).isEqualTo(1);
        assertThat(part.getFromLine()).isEqualTo("1");
        assertThat(part.getToLine()).isEqualTo("30");
        assertThat(part.getContent()).isEqualTo(content);
        // The single part must recombine losslessly.
        assertThat(PxChunk.combine(new ArrayList<>(parts)).getContent()).isEqualTo(content);
    }

    @Test
    void largeFileSplitsAtLineBoundariesAndRecombinesLosslessly() throws Exception {
        // 500 lines of 100 chars each (~50k chars) → several parts, split at line boundaries.
        String content = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "L" + i + " " + "x".repeat(97))   // 1 + 3 + 1 + 97 = 102 chars/line
                .collect(Collectors.joining("\n"));
        File f = tempDir.resolve("large.txt").toFile();
        Files.writeString(f.toPath(), content);

        List<PxChunk> parts = uut.chunk(f).toList();

        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.getMimeType()).isEqualTo(DefaultLineBreakTextChunker.MIME);
            assertThat(p.getMetadata().get(PxChunk.PXCHUNK_TYPE)).isEqualTo(PxChunk.TEXT_MARKER);
            assertThat(p.getId()).isEqualTo(f.getAbsolutePath() + ":text");   // same ID for every part
            assertThat(p.getTotal()).isEqualTo(parts.size());
            // Never split in the middle of a line: no part may end without a '\n' except the last.
            boolean isLast = p.getPart() == parts.size() - 1;
            if (!isLast) {
                assertThat(p.getContent()).endsWith("\n");
            }
        });
        // Parts are ordered and cover the whole file.
        assertThat(parts.get(0).getPart()).isZero();
        assertThat(parts.get(parts.size() - 1).getPart()).isEqualTo(parts.size() - 1);
        // The key invariant: recombining the parts reconstructs the original content.
        assertThat(PxChunk.combine(new ArrayList<>(parts)).getContent()).isEqualTo(content);
    }

    @Test
    void overlongSingleLineBecomesItsOwnPart() throws Exception {
        // A single line longer than PART_SIZE must not be split in the middle.
        String content = "a".repeat(30_000);
        File f = tempDir.resolve("oneline.txt").toFile();
        Files.writeString(f.toPath(), content);

        List<PxChunk> parts = uut.chunk(f).toList();

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).getContent()).isEqualTo(content);
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
