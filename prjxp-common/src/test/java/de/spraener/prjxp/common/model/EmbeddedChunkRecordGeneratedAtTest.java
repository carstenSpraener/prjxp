package de.spraener.prjxp.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedChunkRecordGeneratedAtTest {

    private final ObjectMapper objMapper = new ObjectMapper();

    private EmbeddedChunkRecord record(Long generatedAt) {
        return new EmbeddedChunkRecord(
                "chunk-1",
                "text/markdown",
                "docs/readme.md",
                "parent-1",
                1,
                1,
                "1",
                "10",
                100,
                0,
                Map.of(),
                "content",
                new float[]{0.5f},
                null,
                "test-project",
                generatedAt
        );
    }

    @Test
    void toPxChunk_stampsGeneratedAt_whenPositive() {
        PxChunk chunk = record(1234L).toPxChunk();

        assertThat(chunk.getMetadata().get("generated_at")).isEqualTo("1234");
    }

    @Test
    void toPxChunk_omitsGeneratedAt_whenNullOrZero() {
        assertThat(record(null).toPxChunk().getMetadata()).doesNotContainKey("generated_at");
        assertThat(record(0L).toPxChunk().getMetadata()).doesNotContainKey("generated_at");
    }

    @Test
    void from_readsGeneratedAt_fromMetadata() {
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("id-1");
            c.setFile("a.java");
            c.setContent("x");
            c.getMetadata().put("generated_at", "999");
        });

        assertThat(EmbeddedChunkRecord.from(chunk, new float[]{0.5f}).generatedAt()).isEqualTo(999L);
    }

    @Test
    void from_nullGeneratedAt_whenMetadataAbsent() {
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("id-2");
            c.setFile("a.java");
            c.setContent("x");
        });

        assertThat(EmbeddedChunkRecord.from(chunk, new float[]{0.5f}).generatedAt()).isNull();
    }

    @Test
    void jackson_roundTrip_missingField_isNull() throws Exception {
        ObjectNode node = (ObjectNode) objMapper.readTree(objMapper.writeValueAsString(record(5L)));
        node.remove("generatedAt");
        String stripped = node.toString();

        EmbeddedChunkRecord copy = objMapper.readValue(stripped, EmbeddedChunkRecord.class);

        assertThat(copy.generatedAt()).isNull();
    }

    @Test
    void jackson_roundTrip_withField() throws Exception {
        EmbeddedChunkRecord copy = objMapper.readValue(
                objMapper.writeValueAsString(record(5L)), EmbeddedChunkRecord.class);

        assertThat(copy.generatedAt()).isEqualTo(5L);
    }
}
