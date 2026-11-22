package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
        // fromLine is the 0-based index of the first content line, so the stored
        // (1-based, inclusive) range of a single-line content at index 1 is 2..2.
        assertThat(chunk.getFromLine()).isEqualTo("2");
        assertThat(chunk.getToLine()).isEqualTo("2");
    }

    @Test
    void splitContent_emptyContent_emptyList() {
        assertThat(splitter.splitContent("", 1, 2, supplier)).isEmpty();
    }

    @Test
    void splitContent_longContent_multipleChunksWithProgression() {
        // 25 characters containing two newlines (three source lines, 0-based start 0).
        String content = "abcdefgh\nklmnopqrst\nuvwxy";

        List<PxChunk> chunks = splitter.splitContent(content, 0, 3, supplier);

        assertThat(chunks.size()).isGreaterThan(1);
        // parts progress 0, 1, ... and total equals the final chunk count on every chunk
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getPart()).isEqualTo(i);
            assertThat(chunks.get(i).getTotal()).isEqualTo(chunks.size());
            assertThat(chunks.get(i).getSize()).isLessThanOrEqualTo(10);
        }
        // fromLine/toLine are 1-based and inclusive; a chunk that starts mid-line maps its
        // first partial line to that source line, so no off-by-one creeps in.
        assertThat(chunks.stream().map(PxChunk::getFromLine).toList())
                .containsExactly("1", "1", "2", "3");
        assertThat(chunks.stream().map(PxChunk::getToLine).toList())
                .containsExactly("2", "2", "3", "3");
    }

    @Test
    void splitContent_overlapDoesNotDriftLineNumbers() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            content.append("line ").append(i).append('\n');
        }
        String source = content.toString();
        ContentSplitter overlapping = new ContentSplitter(50, 20);

        List<PxChunk> chunks = overlapping.splitContent(source, 0, 60, supplier);

        assertThat(chunks.size()).isGreaterThan(3);
        int offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            PxChunk chunk = chunks.get(i);
            int lastOffset = offset + chunk.getContent().length() - 1;
            assertThat(chunk.getFromLine()).as("chunk %d fromLine", i)
                    .isEqualTo(String.valueOf(1 + countNewlines(source, 0, offset)));
            assertThat(chunk.getToLine()).as("chunk %d toLine", i)
                    .isEqualTo(String.valueOf(1 + countNewlines(source, 0, lastOffset)));
            int overlap = i + 1 < chunks.size() ? chunks.get(i + 1).getOverlap() : 0;
            offset = lastOffset + 1 - overlap;
        }
    }

    private static int countNewlines(String content, int fromInclusive, int toExclusive) {
        int count = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
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
        // ChunkRange lines are [fromLine, toLine): toCode() is "x\n" (2 chars) -> single chunk.
        ChunkRange range = new ChunkRange(0, 1, List.of("x", "y"));

        List<PxChunk> chunks = splitter.splitContent(range, supplier);

        assertThat(chunks).hasSize(1);
        PxChunk chunk = chunks.get(0);
        assertThat(chunk.getContent()).isEqualTo("x\n");
        assertThat(chunk.getSize()).isEqualTo(2); // "x\n".length()
        assertThat(chunk.getTotal()).isEqualTo(1);
        assertThat(chunk.getPart()).isZero();
        assertThat(chunk.getFromLine()).isEqualTo("1");
        assertThat(chunk.getToLine()).isEqualTo("1");
    }

    @Test
    void splitContent_emojiOnChunkEnd_neverSplitsSurrogatePair() {
        // 🧱 occupies chars 9 and 10 — exactly where the first 10-char chunk would end.
        String content = "abcdefghi\uD83E\uDDF1jklmnopqrstuvwxyz";

        List<PxChunk> chunks = splitter.splitContent(content, 1, 2, supplier);

        assertNoUnpairedSurrogates(chunks);
        assertThat(splitter.unsplit(chunks)).isEqualTo(content);
    }

    @Test
    void splitContent_emojiOnChunkStart_neverStartsWithLowSurrogate() {
        // 🧱 occupies chars 6 and 7; with chunkSize 10 / overlap 3 the next chunk would start at 7.
        String content = "abcdef\uD83E\uDDF1hijklmnopqrstuvwxyz";

        List<PxChunk> chunks = splitter.splitContent(content, 1, 2, supplier);

        assertNoUnpairedSurrogates(chunks);
        assertThat(splitter.unsplit(chunks)).isEqualTo(content);
    }

    /** Every chunk must be valid UTF-16 and encodable as UTF-8 (no lone surrogate halves). */
    private static void assertNoUnpairedSurrogates(List<PxChunk> chunks) {
        for (PxChunk chunk : chunks) {
            String content = chunk.getContent();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertThat(i + 1).as("high surrogate at end of chunk '%s'", content).isLessThan(content.length());
                    assertThat(Character.isLowSurrogate(content.charAt(i + 1)))
                            .as("unpaired high surrogate in chunk '%s'", content).isTrue();
                    i++;
                } else {
                    assertThat(Character.isLowSurrogate(c))
                            .as("unpaired low surrogate in chunk '%s'", content).isFalse();
                }
            }
            assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(content))
                    .as("chunk '%s' must be UTF-8 encodable", content).isTrue();
        }
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
