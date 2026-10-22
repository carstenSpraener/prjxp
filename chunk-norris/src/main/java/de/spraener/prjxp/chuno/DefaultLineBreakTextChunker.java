package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Language-agnostic <em>fallback</em> chunker for (textual) files that no specific
 * {@link PxChunker} handles: splits the content at line breaks into parts of at most
 * {@value #PART_SIZE} characters, mime {@value #MIME}, metadata marker
 * {@link PxChunk#PXCHUNK_TYPE}={@link PxChunk#TEXT_MARKER}.
 * <p>
 * Unlike {@link FullTextChunker} (gated per project via {@code fulltext}, mime
 * {@code file/text}, marker {@link PxChunk#FULLTEXT_MARKER} — a lossless copy that only
 * serves {@code readFile} and is excluded from search), this chunker produces
 * <em>searchable</em> chunks: it carries the {@link PxChunk#TEXT_MARKER} (not
 * {@link PxChunk#FULLTEXT_MARKER}), so it is <b>not</b> excluded by the
 * {@code != FULLTEXT} filter in the search services.
 * <p>
 * It is deliberately <b>not</b> registered as a {@code @ChunkNorrisComponent}/{@code @Chunker}
 * — that would make it match every file like {@link FullTextChunker} and defeat the
 * fallback semantics. Instead it is a plain Spring {@link Component} injected into
 * {@link ChunkerFactory}, which adds it only when no specific chunker matches a
 * (whitelisted) file — see {@link ChunkerFactory#createChunker(File)}.
 */
@Component
public class DefaultLineBreakTextChunker implements PxChunker {
    public static final String MIME = "text/plain";
    private static final int PART_SIZE = 20_000;   // ~500 lines, like FullTextChunker
    private static final int SNIFF_BYTES = 8_192;

    @Override
    public boolean matches(File f) {
        return true;   // catch-all; only ever invoked via the ChunkerFactory fallback
    }

    @Override
    public Stream<PxChunk> chunk(File f) {
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return Stream.of();
            }
            String sniff = content.substring(0, Math.min(SNIFF_BYTES, content.length()));
            if (sniff.indexOf('\0') >= 0) {
                return Stream.of();   // binary file — skip
            }
            return splitByLine(content, f).stream();
        } catch (IOException e) {
            return Stream.of();
        }
    }

    /**
     * Splits the content at line breaks, grouping consecutive lines into parts of at most
     * {@value #PART_SIZE} characters. Lines are never split in the middle — a single line
     * longer than {@value #PART_SIZE} becomes a part of its own. Each part carries
     * 1-based {@code fromLine}/{@code toLine} (inclusive) bookkeeping.
     */
    private List<PxChunk> splitByLine(String content, File f) {
        String[] lines = content.split("\n", -1);
        List<PxChunk> chunks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int part = 0;
        int chunkStartLine = 1;   // 1-based line number of the first line in the current part

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int appended = line.length() + (sb.length() > 0 ? 1 : 0);   // +1 for the '\n'
            if (sb.length() > 0 && sb.length() + appended > PART_SIZE) {
                // Keep the parts contiguous substrings of the original content: include the
                // trailing '\n' (the separator to the next line) so that PxChunk.combine /
                // ContentSplitter.unsplit can reconstruct the file losslessly.
                sb.append('\n');
                chunks.add(createPart(sb.toString(), chunkStartLine, i, part, f));
                sb.setLength(0);
                part++;
                chunkStartLine = i + 1;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (sb.length() > 0) {
            // The last part has no trailing '\n' (it is the end of the content).
            chunks.add(createPart(sb.toString(), chunkStartLine, lines.length, part, f));
        }
        int total = chunks.size();
        for (PxChunk c : chunks) {
            c.setTotal(total);
        }
        return chunks;
    }

    private PxChunk createPart(String content, int fromLine, int toLine, int part, File f) {
        return PxChunk.create(
                c -> c.setMimeType(MIME),
                // Same ID for every part of the file (like FullTextChunker) so the parts can be
                // recombined via PxChunk.combine; the ":text" suffix distinguishes them from the
                // FullTextChunker's parts (which use the bare absolute path).
                c -> c.setId(f.getAbsolutePath() + ":text"),
                c -> c.setFile(f.getAbsolutePath()),
                c -> c.setPart(part),
                c -> c.setContent(content),
                c -> c.setSize(content.length()),
                c -> c.setOverlap(0),
                c -> c.setFromLine("" + fromLine),
                c -> c.setToLine("" + toLine),
                c -> c.getMetadata().put(PxChunk.PXCHUNK_TYPE, PxChunk.TEXT_MARKER));
    }
}
