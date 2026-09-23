package de.spraener.prjxp.common.reader;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReaderSupport {

    private ReaderSupport() {
    }

    /** Group raw chunks by id (LinkedHashMap, first-seen order), sort each group by part,
     *  PxChunk.combine each group; return units sorted by fromLine ASC, then id. */
    public static List<PxChunk> combineUnits(List<PxChunk> rawChunks) {
        Map<String, List<PxChunk>> groups = new LinkedHashMap<>();
        for (PxChunk chunk : rawChunks) {
            groups.computeIfAbsent(chunk.getId(), id -> new ArrayList<>()).add(chunk);
        }

        List<PxChunk> units = new ArrayList<>(groups.size());
        for (List<PxChunk> parts : groups.values()) {
            parts.sort(Comparator.comparingInt(PxChunk::getPart));
            units.add(PxChunk.combine(parts));
        }

        return units.stream()
                .sorted(Comparator
                        .comparingInt((PxChunk unit) -> parseLine(unit.getFromLine()))
                        .thenComparing(PxChunk::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Trim, backslash->slash, strip all leading slashes, strip trailing slash. null -> null. */
    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        int start = 0;
        int end = normalized.length();
        while (start < end && normalized.charAt(start) == '/') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == '/') {
            end--;
        }
        return normalized.substring(start, end);
    }

    /** Integer.parseInt with trim; null/blank/NumberFormatException -> 0. */
    public static int parseLine(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Integer.parseInt with trim; null/blank/NumberFormatException -> null. */
    public static Integer parseLineOrNull(String value) {
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
