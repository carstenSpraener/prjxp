package de.spraener.prjxp.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedChunkRecordTest {
    private final ObjectMapper objMapper = new ObjectMapper();

    private EmbeddedChunkRecord fullRecord() {
        return new EmbeddedChunkRecord(
                "chunk-1",
                "text/markdown",
                "docs/readme.md",
                "parent-1",
                2,
                3,
                "10",
                "42",
                512,
                64,
                Map.of("author", "spraener"),
                "# Hello\nWorld",
                new float[]{0.5f, -0.25f, 1.0f}
        );
    }

    @Test
    void serialize_withAllFields_producesDocumentedJsonShape() throws Exception {
        String json = objMapper.writeValueAsString(fullRecord());

        JsonNode node = objMapper.readTree(json);
        List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);

        assertThat(fieldNames).containsExactly(
                "id", "mimeType", "file", "parent", "part", "total",
                "fromLine", "toLine", "size", "overlap", "metadata",
                "content", "vector"
        );
        assertThat(node.get("id").asText()).isEqualTo("chunk-1");
        assertThat(node.get("mimeType").asText()).isEqualTo("text/markdown");
        assertThat(node.get("file").asText()).isEqualTo("docs/readme.md");
        assertThat(node.get("parent").asText()).isEqualTo("parent-1");
        assertThat(node.get("part").isInt()).isTrue();
        assertThat(node.get("total").asInt()).isEqualTo(3);
        assertThat(node.get("fromLine").asText()).isEqualTo("10");
        assertThat(node.get("toLine").asText()).isEqualTo("42");
        assertThat(node.get("size").asInt()).isEqualTo(512);
        assertThat(node.get("overlap").asInt()).isEqualTo(64);
        assertThat(node.get("metadata").get("author").asText()).isEqualTo("spraener");
        assertThat(node.get("content").asText()).isEqualTo("# Hello\nWorld");
        assertThat(node.get("vector").isArray()).isTrue();
        assertThat(node.get("vector").size()).isEqualTo(3);
        assertThat(node.get("vector").get(0).floatValue()).isEqualTo(0.5f);
        assertThat(node.get("vector").get(1).floatValue()).isEqualTo(-0.25f);
        assertThat(node.get("vector").get(2).floatValue()).isEqualTo(1.0f);
    }

    @Test
    void serialize_withNullOptionalFields_writesJsonNulls() throws Exception {
        EmbeddedChunkRecord record = new EmbeddedChunkRecord(
                "chunk-2", null, "f.java", null, 1, 1, null, null, 10, 0, Map.of(), "code", new float[]{0.1f}
        );

        JsonNode node = objMapper.readTree(objMapper.writeValueAsString(record));

        assertThat(node.get("mimeType").isNull()).isTrue();
        assertThat(node.get("parent").isNull()).isTrue();
        assertThat(node.get("fromLine").isNull()).isTrue();
        assertThat(node.get("toLine").isNull()).isTrue();
    }

    @Test
    void roundTrip_throughJson_preservesAllFields() throws Exception {
        EmbeddedChunkRecord original = fullRecord();

        EmbeddedChunkRecord copy = objMapper.readValue(objMapper.writeValueAsString(original), EmbeddedChunkRecord.class);

        assertThat(copy).isEqualTo(original);
    }

    @Test
    void roundTrip_withNullOptionalFields_preservesNulls() throws Exception {
        EmbeddedChunkRecord original = new EmbeddedChunkRecord(
                "chunk-3", null, "f.java", null, 1, 1, null, null, 10, 0, Map.of(), "code", new float[]{0.25f}
        );

        EmbeddedChunkRecord copy = objMapper.readValue(objMapper.writeValueAsString(original), EmbeddedChunkRecord.class);

        assertThat(copy).isEqualTo(original);
        assertThat(copy.metadata()).isEmpty();
    }

    @Test
    void from_pxChunkWithVector_copiesAllFields() {
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("id-9");
            c.setMimeType("text/x-java");
            c.setFile("src/A.java");
            c.setParent("parent-9");
            c.setPart(1);
            c.setTotal(2);
            c.setFromLine("5");
            c.setToLine("9");
            c.setSize(100);
            c.setOverlap(20);
            c.getMetadata().put("k", "v");
            c.setContent("class A {}");
        });

        EmbeddedChunkRecord record = EmbeddedChunkRecord.from(chunk, new float[]{0.5f});

        assertThat(record.id()).isEqualTo("id-9");
        assertThat(record.mimeType()).isEqualTo("text/x-java");
        assertThat(record.file()).isEqualTo("src/A.java");
        assertThat(record.parent()).isEqualTo("parent-9");
        assertThat(record.part()).isEqualTo(1);
        assertThat(record.total()).isEqualTo(2);
        assertThat(record.fromLine()).isEqualTo("5");
        assertThat(record.toLine()).isEqualTo("9");
        assertThat(record.size()).isEqualTo(100);
        assertThat(record.overlap()).isEqualTo(20);
        assertThat(record.metadata()).containsEntry("k", "v");
        assertThat(record.content()).isEqualTo("class A {}");
        assertThat(record.vector()).containsExactly(0.5f);
    }

    @Test
    void toPxChunk_fromRecord_preservesAllFields() {
        EmbeddedChunkRecord record = fullRecord();

        PxChunk chunk = record.toPxChunk();

        assertThat(chunk.getId()).isEqualTo("chunk-1");
        assertThat(chunk.getMimeType()).isEqualTo("text/markdown");
        assertThat(chunk.getFile()).isEqualTo("docs/readme.md");
        assertThat(chunk.getParent()).isEqualTo("parent-1");
        assertThat(chunk.getPart()).isEqualTo(2);
        assertThat(chunk.getTotal()).isEqualTo(3);
        assertThat(chunk.getFromLine()).isEqualTo("10");
        assertThat(chunk.getToLine()).isEqualTo("42");
        assertThat(chunk.getSize()).isEqualTo(512);
        assertThat(chunk.getOverlap()).isEqualTo(64);
        assertThat(chunk.getMetadata()).containsEntry("author", "spraener");
        assertThat(chunk.getContent()).isEqualTo("# Hello\nWorld");
    }

    @Test
    void roundTrip_recordToPxChunkAndBack_isStable() {
        EmbeddedChunkRecord original = fullRecord();

        EmbeddedChunkRecord copy = EmbeddedChunkRecord.from(original.toPxChunk(), original.vector());

        assertThat(copy).isEqualTo(original);
    }
}
