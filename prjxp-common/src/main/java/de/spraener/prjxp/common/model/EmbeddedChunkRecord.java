package de.spraener.prjxp.common.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record EmbeddedChunkRecord(
        String id,
        String mimeType,
        String file,
        String parent,
        int part,
        int total,
        String fromLine,
        String toLine,
        int size,
        int overlap,
        Map<String, String> metadata,
        String content,
        float[] vector
) {
    public EmbeddedChunkRecord {
        metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }

    public static EmbeddedChunkRecord from(PxChunk chunk, float[] vector) {
        return new EmbeddedChunkRecord(
                chunk.getId(),
                chunk.getMimeType(),
                chunk.getFile(),
                chunk.getParent(),
                chunk.getPart(),
                chunk.getTotal(),
                chunk.getFromLine(),
                chunk.getToLine(),
                chunk.getSize(),
                chunk.getOverlap(),
                new HashMap<>(chunk.getMetadata()),
                chunk.getContent(),
                vector
        );
    }

    public PxChunk toPxChunk() {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setMimeType(mimeType);
            c.setFile(file);
            c.setParent(parent);
            c.setPart(part);
            c.setTotal(total);
            c.setFromLine(fromLine);
            c.setToLine(toLine);
            c.setSize(size);
            c.setOverlap(overlap);
            c.getMetadata().putAll(metadata);
            c.setContent(content);
        });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmbeddedChunkRecord other)) return false;
        return part == other.part
                && total == other.total
                && size == other.size
                && overlap == other.overlap
                && Objects.equals(id, other.id)
                && Objects.equals(mimeType, other.mimeType)
                && Objects.equals(file, other.file)
                && Objects.equals(parent, other.parent)
                && Objects.equals(fromLine, other.fromLine)
                && Objects.equals(toLine, other.toLine)
                && Objects.equals(metadata, other.metadata)
                && Objects.equals(content, other.content)
                && Arrays.equals(vector, other.vector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, mimeType, file, parent, part, total, fromLine, toLine, size, overlap, metadata, content, Arrays.hashCode(vector));
    }
}
