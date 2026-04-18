package de.spraener.prjxp.common.model;

import de.spraener.prjxp.common.util.ContentSplitter;
import lombok.Data;
import lombok.extern.java.Log;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Data
@Log
public class PxChunk {
    public static final String PXCHUNK_ID = "pxchunk_id";
    public static final String PXCHUNK_MIME_TYPE = "pxchunk_mimeType";
    public static final String PXCHUNK_FILE = "pxchunk_file";
    public static final String PXCHUNK_PARENT = "pxchunk_parent";
    public static final String PXCHUNK_PART = "pxchunk_part";
    public static final String PXCHUNK_TOTAL = "pxchunk_total";
    public static final String PXCHUNK_FROM_LINE = "pxchunk_fromLine";
    public static final String PXCHUNK_TO_LINE = "pxchunk_toLine";
    public static final String PXCHUNK_SIZE = "pxchunk_size";
    public static final String PXCHUNK_OVERLAP = "pxchunk_overlap";
    public static final String PXCHUNK_METADATA = "pxchunk_metadata";

    private String id;
    private String mimeType;
    private String file;
    private String parent;
    private int part;
    private int total;
    private String fromLine;
    private String toLine;
    private int size;
    private int overlap;
    private HashMap<String, String> metadata = new HashMap<>();

    private String content;

    private PxChunk() {
    }

    public static Map<String, String> metadataAsMap(PxChunk chunk) {
        Map<String, String> map = new HashMap<>();
        ifNotNull(chunk.id, () -> map.put(PXCHUNK_ID, chunk.id));
        ifNotNull(chunk.mimeType, () -> map.put(PXCHUNK_MIME_TYPE, chunk.mimeType));
        ifNotNull(chunk.file, () -> map.put(PXCHUNK_FILE, chunk.file));
        ifNotNull(chunk.parent, () -> map.put(PXCHUNK_PARENT, chunk.parent));
        ifNotNull(chunk.part, () -> map.put(PXCHUNK_PART, "" + chunk.part));
        ifNotNull(chunk.total, () -> map.put(PXCHUNK_TOTAL, "" + chunk.total));
        ifNotNull(chunk.fromLine, () -> map.put(PXCHUNK_FROM_LINE, chunk.fromLine));
        ifNotNull(chunk.toLine, () -> map.put(PXCHUNK_TO_LINE, chunk.toLine));
        ifNotNull(chunk.size, () -> map.put(PXCHUNK_SIZE, "" + chunk.size));
        ifNotNull(chunk.overlap, () -> map.put(PXCHUNK_OVERLAP, "" + chunk.overlap));
        for (var e : chunk.getMetadata().entrySet()) {
            map.put(PXCHUNK_METADATA + "." + e.getKey(), e.getValue());
        }
        return map;
    }

    private static void ifNotNull(Object value, Runnable r) {
        if (value != null) {
            r.run();
        }
    }

    public static PxChunk fromContentAndMap(String content, Map<String, Object> objMetadata) {
        Map<String, String> metadata = new HashMap<>();
        objMetadata.forEach((key, value) -> {
            if (value != null) {
                metadata.put(key, value.toString());
            }
        });
        PxChunk chunk = new PxChunk();
        chunk.setContent(content);

        // 1. System-Felder zurückmappen
        // ifPresent-Logik oder einfache Zuweisung über deine Konstanten
        chunk.setId((metadata.get(PXCHUNK_ID).toString()));
        chunk.setMimeType(metadata.get(PXCHUNK_MIME_TYPE));
        chunk.setFile(metadata.get(PXCHUNK_FILE));
        chunk.setParent(metadata.get(PXCHUNK_PARENT));

        // Numerische Felder mit sicherem Parsing
        if (metadata.containsKey(PXCHUNK_PART)) chunk.setPart(Integer.parseInt(metadata.get(PXCHUNK_PART)));
        if (metadata.containsKey(PXCHUNK_TOTAL)) chunk.setTotal(Integer.parseInt(metadata.get(PXCHUNK_TOTAL)));
        if (metadata.containsKey(PXCHUNK_SIZE)) chunk.setSize(Integer.parseInt(metadata.get(PXCHUNK_SIZE)));
        if (metadata.containsKey(PXCHUNK_OVERLAP)) chunk.setOverlap(Integer.parseInt(metadata.get(PXCHUNK_OVERLAP)));

        chunk.setFromLine(metadata.get(PXCHUNK_FROM_LINE));
        chunk.setToLine(metadata.get(PXCHUNK_TO_LINE));

        // 2. Dynamische Metadaten extrahieren
        // Wir suchen nach Keys, die mit dem Präfix "PXCHUNK_METADATA." starten
        String prefix = PXCHUNK_METADATA + ".";
        metadata.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                String realKey = key.substring(prefix.length());
                chunk.getMetadata().put(realKey, value);
            }
        });

        return chunk;
    }

    public static PxChunk create(Consumer<PxChunk>... modifier) {
        PxChunk chunk = new PxChunk();
        if (modifier != null) {
            for (Consumer<PxChunk> m : modifier) {
                m.accept(chunk);
            }
        }
        return chunk;
    }

    public static PxChunk createPxChunk(String mimeType,
                                        int fromLine,
                                        int toLine,
                                        int overlap,
                                        String parentID,
                                        String chunkID,
                                        File f,
                                        String content,
                                        Consumer<Map<String, Object>>... metaDataModifier
    ) {
        Map<String, Object> metaData = new HashMap<>();
        metaData.put(PxChunk.PXCHUNK_FILE, f.getAbsolutePath());
        metaData.put(PxChunk.PXCHUNK_ID, chunkID);
        metaData.put(PxChunk.PXCHUNK_PARENT, parentID);
        metaData.put(PxChunk.PXCHUNK_SIZE, String.valueOf(content.length()));
        metaData.put(PxChunk.PXCHUNK_MIME_TYPE, mimeType);
        metaData.put(PxChunk.PXCHUNK_FROM_LINE, "" + fromLine);
        metaData.put(PxChunk.PXCHUNK_TO_LINE, "" + toLine);
        metaData.put(PxChunk.PXCHUNK_OVERLAP, "" + overlap);
        if (metaDataModifier != null) {
            for (Consumer<Map<String, Object>> modifier : metaDataModifier) {
                modifier.accept(metaData);
            }
        }
        return PxChunk.fromContentAndMap(content, metaData);
    }

    public static PxChunk combine(List<PxChunk> chunkList) {
        if (chunkList.isEmpty()) {
            return null;
        }
        Collections.sort(chunkList, (c1, c2) -> c1.part - c2.part);
        PxChunk combined = new PxChunk();
        PxChunk root = chunkList.get(0);
        combined.id = root.id;
        combined.mimeType = root.mimeType;
        combined.file = root.file;
        combined.parent = root.parent;
        combined.fromLine = root.fromLine;
        combined.toLine = root.toLine;
        combined.size = root.size;
        combined.overlap = root.overlap;
        combined.metadata = new HashMap<>(root.metadata);
        String content = new ContentSplitter(root.size, root.overlap)
                .unsplit(chunkList);
        combined.content = content;
        return combined;
    }
}
