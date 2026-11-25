package de.spraener.prjxp.lucene;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * One live indexed chunk. {@code nodeId} uniquely identifies its project, logical id, and part.
 */
public record VectorChunk(String nodeId, String id, String project, int part,
                          float[] vector, String content, Map<String, String> metadata) {

    public static String nodeIdFor(String project, String id, int part) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(id, "id");
        String tuple = project.length() + ":" + project + id.length() + ":" + id + ":" + part;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tuple.getBytes(StandardCharsets.UTF_8));
    }
}
