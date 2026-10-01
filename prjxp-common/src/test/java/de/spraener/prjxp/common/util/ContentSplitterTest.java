package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ContentSplitter}.
 */
class ContentSplitterTest {

    private final ContentSplitter splitter = new ContentSplitter(10, 3);

    // Deviation from the phase doc: PxChunk's no-arg constructor is private in the
    // source, so the supplier uses the public static factory instead of `new PxChunk()`.
    private final Supplier<PxChunk> supplier = () -> PxChunk.create();

    @Test
    void splitContent_shortContent_singleChunk() {
        List<PxChunk> chunks = splitter.splitContent("short", 1, 2, supplier);

        assertThat(chunks).hasSize(1);
        PxChunk chunk = chunks.get(0);
        assertThat(chunk.getTotal()).isEqualTo(1);
        assertThat(chunk.getPart()).isZero();
        assertThat(chunk.getContent()).isEqualTo("short");
        assertThat(chunk.getSize()).isEqualTo(5);
        assertThat(chunk.getOverlap()).isZero();
        assertThat(chunk.getFromLine()).isEqualTo("1");
        assertThat(chunk.getToLine()).isEqualTo("2");
    }

    @Test
    void splitContent_emptyContent_emptyList() {
        assertThat(splitter.splitContent("", 1, 2, supplier)).isEmpty();
    }

    @Test
    void splitContent_longContent_multipleChunksWithProgression() {
        // 25 characters containing two newlines.
        String content = "abcdefgh\nklmnopqrst\nuvwxy";

        List<PxChunk> chunks = splitter.splitContent(content, 1, 26, supplier);

        assertThat(chunks.size()).isGreaterThan(1);
        // parts progress 0, 1, ... and total equals the final chunk count on every chunk
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getPart()).isEqualTo(i);
            assertThat(chunks.get(i).getTotal()).isEqualTo(chunks.size());
            assertThat(chunks.get(i).getSize()).isLessThanOrEqualTo(10);
        }
        // fromLine/toLine progress by the number of newlines contained in each chunk
        assertThat(chunks.stream().map(PxChunk::getFromLine).toList())
                .containsExactly("1", "2", "3", "4");
        assertThat(chunks.stream().map(PxChunk::getToLine).toList())
                .containsExactly("2", "3", "4", "4");
    }

    @Test
    void splitContent_stringOverload_delegates() {
        // 14 chars -> two chunks; the loop ends without hitting `chunkStart > length`.
        String content = "abcdefghijklmn";

        List<PxChunk> fromString = splitter.splitContent(content, 1, 2, supplier);
        List<PxChunk> fromStringBuilder = splitter.splitContent(new StringBuilder(content), 1, 2, supplier);

        assertThat(fromString).isEqualTo(fromStringBuilder);
    }

    @Test
    void splitContent_chunkRangeOverload_delegates() {
        // toCode() = "x\ny\n" which is 4 characters (the phase doc's "8 < 10" was a
        // miscount) -> still below chunkSize 10, so the single-chunk branch is taken.
        ChunkRange range = new ChunkRange(0, 1, List.of("x", "y"));

        List<PxChunk> chunks = splitter.splitContent(range, supplier);

        assertThat(chunks).hasSize(1);
        PxChunk chunk = chunks.get(0);
        assertThat(chunk.getContent()).isEqualTo("x\ny\n");
        assertThat(chunk.getSize()).isEqualTo(4); // "x\ny\n".length()
        assertThat(chunk.getTotal()).isEqualTo(1);
        assertThat(chunk.getPart()).isZero();
        assertThat(chunk.getFromLine()).isEqualTo("0");
        assertThat(chunk.getToLine()).isEqualTo("1");
    }

    @Test
    void unsplit_reconstructsAndSkipsTinyChunks() {
        PxChunk c1 = PxChunk.create(c -> c.setContent("hello world")); // 11 chars, overlap 0
        PxChunk c2 = PxChunk.create(c -> {
            c.setContent("ab"); // 2 chars <= overlap of 5 -> skipped entirely
            c.setOverlap(5);
        });

        assertThat(splitter.unsplit(List.of(c1, c2))).isEqualTo("hello world");
        assertThat(splitter.unsplit(List.<PxChunk>of())).isEmpty();

        // Roundtrip: split -> unsplit reproduces the original content.
        String original = "abcdefghij\nklmnopqrst"; // 21 chars -> chunks [0,10), [7,17), [14,21)
        List<PxChunk> chunks = splitter.splitContent(original, 1, 2, supplier);
        assertThat(splitter.unsplit(chunks)).isEqualTo(original);
    }
}
