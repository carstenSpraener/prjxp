package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.util.ContentSplitter;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Stream;

/**
 * Language-agnostic chunker that stores a lossless copy of every (textual) file:
 * one unit per file, parts via {@link ContentSplitter} with zero overlap,
 * mime {@value #MIME}, metadata marker {@link PxChunk#PXCHUNK_TYPE}={@link PxChunk#FULLTEXT_MARKER}.
 * <p>
 * {@link #matches(File)} is always true — the whitelist veto runs upstream in
 * {@link ChunkProcess}, so this chunker only ever sees files that are indexed anyway.
 * It is gated per project via {@code fulltext} in the {@code ProjectDefinition}.
 */
@Component
@ChunkNorrisComponent
@Chunker
public class FullTextChunker implements PxChunker {
    public static final String MIME = "file/text";
    private static final int PART_SIZE = 20_000;   // ~500 lines
    private static final int SNIFF_BYTES = 8_192;

    @Override
    public boolean matches(File f) {
        return true;
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
            int lineCount = content.split("\n", -1).length;   // fromLine/toLine bookkeeping
            return new ContentSplitter(PART_SIZE, 0).splitContent(content, 1, lineCount + 1,
                    () -> PxChunk.create(
                            c -> c.setMimeType(MIME),
                            c -> c.setId(f.getAbsolutePath()),
                            c -> c.setFile(f.getAbsolutePath()),
                            c -> c.getMetadata().put(PxChunk.PXCHUNK_TYPE, PxChunk.FULLTEXT_MARKER)))
                    .stream();
        } catch (IOException e) {
            return Stream.of();
        }
    }
}
