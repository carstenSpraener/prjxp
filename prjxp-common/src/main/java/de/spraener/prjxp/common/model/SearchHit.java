package de.spraener.prjxp.common.model;

import java.util.Map;

public record SearchHit(
        String chunkId,
        double score,
        String file,
        Integer lineFrom,
        Integer lineTo,
        String snippet,
        String source,
        Map<String, String> metadata) {

    public static final int SNIPPET_MAX = 240;

    public static String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.length() <= SNIPPET_MAX ? content : content.substring(0, SNIPPET_MAX);
    }

    public static SearchHit from(PxChunk chunk, double score, String snippet, String source) {
        return new SearchHit(
                chunk.getId(),
                score,
                chunk.getFile(),
                parseLine(chunk.getFromLine()),
                parseLine(chunk.getToLine()),
                snippet,
                source,
                chunk.getMetadata()
        );
    }

    private static Integer parseLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
