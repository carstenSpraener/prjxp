package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.model.PxChunk;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Data
@RequiredArgsConstructor
public class ContentSplitter {
    private final int chunkSize;
    private final int overlap;

    public List<PxChunk> splitContent(ChunkRange range, Supplier<PxChunk> chunkSupplier) {
        return splitContent(range.toCode(), range.getFromLine(), range.getToLine(), chunkSupplier);
    }

    public List<PxChunk> splitContent(String content, int fromLine, int toLine, Supplier<PxChunk> chunkSupplier) {
        return splitContent(new StringBuilder(content), fromLine, toLine, chunkSupplier);
    }

    public List<PxChunk> splitContent(StringBuilder content, int fromLine, int toLine, Supplier<PxChunk> chunkSupplier) {
        List<PxChunk> chunks = new ArrayList<>();
        if( content.isEmpty() ) {
            return chunks;
        }
        if (content.length() < chunkSize) {
            PxChunk chunk = chunkSupplier.get();
            chunk.setTotal(1);
            chunk.setPart(0);
            chunk.setContent(content.toString());
            chunk.setSize(chunk.getContent().length());
            chunk.setOverlap(0);
            chunk.setFromLine("" + fromLine);
            chunk.setToLine("" + toLine);
            chunks.add(chunk);
        } else {
            int total = 0;
            int chunkStart = 0;
            int chunkStartLine = fromLine;
            int previousEnd = 0;
            for (int i = 0; chunkStart < content.length(); i++) {
                int chunkEnd = alignEnd(content, chunkStart, Math.min(chunkStart + chunkSize, content.length()));
                String chunkContent = content.substring(chunkStart, chunkEnd);
                PxChunk chunk = chunkSupplier.get();
                chunk.setContent(chunkContent);
                chunk.setTotal(total);
                chunk.setPart(i);
                chunk.setSize(chunk.getContent().length());
                // The real overlap can differ by one when a boundary had to be moved away
                // from the middle of a surrogate pair; store the actual shared prefix so
                // that unsplit() reconstructs the content losslessly.
                chunk.setOverlap(i == 0 ? this.overlap : Math.max(0, previousEnd - chunkStart));
                int lineCount = StringUtils.countOccurrencesOf(chunkContent, "\n");
                chunk.setFromLine("" + chunkStartLine);
                chunk.setToLine("" + (chunkStartLine + lineCount));
                chunkStartLine += lineCount;

                chunks.add(chunk);
                previousEnd = chunkEnd;
                total++;
                if (chunkEnd >= content.length()) {
                    break;
                }
                int nextStart = alignStart(content, chunkEnd - this.overlap);
                chunkStart = Math.max(nextStart, chunkStart + 1);   // never stop making progress
            }
            chunks.stream().forEach(c -> c.setTotal(chunks.size()));
        }
        return chunks;
    }

    /**
     * Moves a chunk end that would split a surrogate pair one character to the left (or, for
     * degenerate chunk sizes, one to the right) so every chunk stays valid UTF-16.
     */
    private static int alignEnd(CharSequence content, int start, int end) {
        if (end >= content.length()) {
            return content.length();
        }
        if (end <= start) {
            return Math.min(content.length(), start + 1);
        }
        boolean splitsPair = Character.isHighSurrogate(content.charAt(end - 1))
                && Character.isLowSurrogate(content.charAt(end));
        if (!splitsPair) {
            return end;
        }
        return end - 1 > start ? end - 1 : end + 1;
    }

    /** Moves a chunk start that points at the second half of a surrogate pair back onto the pair. */
    private static int alignStart(CharSequence content, int start) {
        if (start > 0 && start < content.length()
                && Character.isHighSurrogate(content.charAt(start - 1))
                && Character.isLowSurrogate(content.charAt(start))) {
            return start - 1;
        }
        return start;
    }

    public String unsplit(List<PxChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (var c : chunks) {
            var content = c.getContent();
            // Remove contentPrefix
            // content = content.substring(content.indexOf('\n') + 1);
            if (sb.isEmpty()) {
                sb.append(content);
            } else if (content.length() > c.getOverlap()) {
                sb.append(content.substring(c.getOverlap()));
            }
        }
        return sb.toString();
    }
}
