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
    private String contentPrefix = "";

    public ContentSplitter withContentPrefix(String contentPrefix) {
        this.contentPrefix = contentPrefix;
        return this;
    }

    public List<PxChunk> splitContent(ChunkRange range, Supplier<PxChunk> chunkSupplier) {
        return splitContent(range.toCode(), range.getFromLine(), range.getToLine(), chunkSupplier);
    }

    public List<PxChunk> splitContent(String content, int fromLine, int toLine, Supplier<PxChunk> chunkSupplier) {
        return splitContent(new StringBuilder(content), fromLine, toLine, chunkSupplier);
    }

    public List<PxChunk> splitContent(StringBuilder content, int fromLine, int toLine, Supplier<PxChunk> chunkSupplier) {
        List<PxChunk> chunks = new ArrayList<>();
        int activeChunkSize = chunkSize - contentPrefix.length() - 1; // New Line mit einrechnen
        if (content.length() < activeChunkSize) {
            PxChunk chunk = chunkSupplier.get();
            chunk.setContent(contentPrefix + "\n" + content.toString());
            chunk.setTotal(1);
            chunk.setPart(0);
            chunk.setSize(chunk.getContent().length());
            chunk.setOverlap(0);
            chunk.setFromLine("" + fromLine);
            chunk.setToLine("" + toLine);
            chunks.add(chunk);
        } else {
            int total = 0;
            int chunkStart = 0;
            int chunkStartLine = fromLine;
            for (int i = 0; chunkStart < content.length(); i++) {
                String chunkContent = content.substring(chunkStart, Math.min(chunkStart + activeChunkSize, content.length()));
                PxChunk chunk = chunkSupplier.get();
                chunk.setContent(contentPrefix + "\n" + chunkContent);
                chunk.setTotal(total);
                chunk.setPart(i);
                chunk.setSize(chunk.getContent().length());
                chunk.setOverlap(this.overlap);
                chunk.setFromLine("" + chunkStartLine);
                chunk.setToLine("" + chunkStartLine + StringUtils.countOccurrencesOf(chunkContent, "\n"));
                chunkStartLine += StringUtils.countOccurrencesOf(chunkContent, "\n");

                chunks.add(chunk);
                chunkStart += activeChunkSize - overlap;
                total++;
                if (chunkStart > content.length()) {
                    break;
                }
            }
            chunks.stream().forEach(c -> c.setTotal(chunks.size()));
        }
        return chunks;
    }

    public String unsplit(List<PxChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (var c : chunks) {
            var content = c.getContent();
            // Remove contentPrefix
            content = content.substring(content.indexOf('\n') + 1);
            if (sb.isEmpty()) {
                sb.append(content);
            } else if (content.length() > c.getOverlap()) {
                sb.append(content.substring(c.getOverlap()));
            }
        }
        return sb.toString();
    }
}
