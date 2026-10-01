package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PxChunkFactoryMethodsTest {

    @Test
    void metadataAsMap_mapsAllNonNullFields() {
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("id-1");
            c.setMimeType("text/x-java");
            c.setFile("/path/Foo.java");
            c.setParent("parent-1");
            c.setPart(2);
            c.setTotal(5);
            c.setFromLine("10");
            c.setToLine("20");
            c.setSize(100);
            c.setOverlap(10);
            c.setEmbeddingPrefix("prefix");
            c.setProject("prjxp");
        });
        chunk.getMetadata().put("custom", "value");

        Map<String, String> map = PxChunk.metadataAsMap(chunk);

        assertThat(map)
                .containsEntry(PxChunk.PXCHUNK_ID, "id-1")
                .containsEntry(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java")
                .containsEntry(PxChunk.PXCHUNK_FILE, "/path/Foo.java")
                .containsEntry(PxChunk.PXCHUNK_PARENT, "parent-1")
                .containsEntry(PxChunk.PXCHUNK_PART, "2")
                .containsEntry(PxChunk.PXCHUNK_TOTAL, "5")
                .containsEntry(PxChunk.PXCHUNK_FROM_LINE, "10")
                .containsEntry(PxChunk.PXCHUNK_TO_LINE, "20")
                .containsEntry(PxChunk.PXCHUNK_SIZE, "100")
                .containsEntry(PxChunk.PXCHUNK_OVERLAP, "10")
                .containsEntry(PxChunk.PXCHUNK_EMBEDDING_PREFIX, "prefix")
                .containsEntry(PxChunk.PXCHUNK_PROJECT, "prjxp")
                .containsEntry("pxchunk_metadata.custom", "value");
        assertThat(map).hasSize(13);
    }

    @Test
    void fromContentAndMap_nullValue_skipped() {
        Map<String, Object> obj = new HashMap<>();
        obj.put(PxChunk.PXCHUNK_ID, "id-1");
        obj.put(PxChunk.PXCHUNK_MIME_TYPE, null);

        PxChunk chunk = PxChunk.fromContentAndMap("content", obj);

        assertThat(chunk.getId()).isEqualTo("id-1");
        assertThat(chunk.getMimeType()).isNull();
    }

    @Test
    void fromContentAndMap_numericKeys_parsed() {
        Map<String, Object> obj = new HashMap<>();
        obj.put(PxChunk.PXCHUNK_ID, "id-1");
        obj.put(PxChunk.PXCHUNK_PART, "2");
        obj.put(PxChunk.PXCHUNK_TOTAL, "5");
        obj.put(PxChunk.PXCHUNK_SIZE, "100");
        obj.put(PxChunk.PXCHUNK_OVERLAP, "10");
        obj.put(PxChunk.PXCHUNK_EMBEDDING_PREFIX, "prefix");

        PxChunk chunk = PxChunk.fromContentAndMap("content", obj);

        assertThat(chunk.getPart()).isEqualTo(2);
        assertThat(chunk.getTotal()).isEqualTo(5);
        assertThat(chunk.getSize()).isEqualTo(100);
        assertThat(chunk.getOverlap()).isEqualTo(10);
        assertThat(chunk.getEmbeddingPrefix()).isEqualTo("prefix");
    }

    @Test
    void fromContentAndMap_prefixedMetadataKey_strippedIntoMetadata() {
        Map<String, Object> obj = new HashMap<>();
        obj.put(PxChunk.PXCHUNK_ID, "id-1");
        obj.put("pxchunk_metadata.foo", "bar");

        PxChunk chunk = PxChunk.fromContentAndMap("content", obj);

        assertThat(chunk.getMetadata()).containsEntry("foo", "bar");
    }

    @Test
    void create_nullModifier_ignored() {
        // The source guards the varargs array itself (modifier != null); a null *array*
        // is the branch to cover. Note: create(lambda, null) would NPE on the null element.
        PxChunk chunk = PxChunk.create((Consumer<PxChunk>[]) null);

        assertThat(chunk).isNotNull();
    }

    @Test
    void create_appliesModifiers() {
        PxChunk chunk = PxChunk.create(c -> c.setId("id-1"));

        assertThat(chunk.getId()).isEqualTo("id-1");
    }

    @Test
    void createPxChunk_fullCall() {
        File f = new File("/tmp/Foo.java");
        String content = "int x = 1;";

        PxChunk chunk = PxChunk.createPxChunk("text/x-java", 1, 10, 2, "parent-1", "chunk-1", f, content,
                m -> m.put("pxchunk_metadata.custom", "v"));

        assertThat(chunk.getId()).isEqualTo("chunk-1");
        assertThat(chunk.getMimeType()).isEqualTo("text/x-java");
        assertThat(chunk.getFile()).isEqualTo(f.getAbsolutePath());
        assertThat(chunk.getParent()).isEqualTo("parent-1");
        assertThat(chunk.getFromLine()).isEqualTo("1");
        assertThat(chunk.getToLine()).isEqualTo("10");
        assertThat(chunk.getOverlap()).isEqualTo(2);
        assertThat(chunk.getSize()).isEqualTo(content.length());
        assertThat(chunk.getContent()).isEqualTo(content);
        assertThat(chunk.getMetadata()).containsEntry("custom", "v");
    }

    @Test
    void createPxChunk_nullModifierArray_ignored() {
        File f = new File("/tmp/Foo.java");

        PxChunk chunk = PxChunk.createPxChunk("text/x-java", 1, 10, 2, "p", "c", f, "content",
                (Consumer<Map<String, Object>>[]) null);

        assertThat(chunk.getId()).isEqualTo("c");
    }

    @Test
    void combine_nullOrEmpty_returnsNull() {
        assertThat(PxChunk.combine(null)).isNull();
        assertThat(PxChunk.combine(List.of())).isNull();
    }
}
