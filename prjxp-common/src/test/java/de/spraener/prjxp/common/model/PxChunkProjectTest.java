package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PxChunkProjectTest {

    @Test
    void metadataAsMapIncludesProjectWhenSet() {
        PxChunk chunk = PxChunk.create(c -> c.setProject("foo"));

        Map<String, String> map = PxChunk.metadataAsMap(chunk);

        assertThat(map).containsEntry(PxChunk.PXCHUNK_PROJECT, "foo");
    }

    @Test
    void metadataAsMapOmitsProjectWhenNull() {
        PxChunk chunk = PxChunk.create(c -> c.setId("some-id"));

        Map<String, String> map = PxChunk.metadataAsMap(chunk);

        assertThat(map).doesNotContainKey(PxChunk.PXCHUNK_PROJECT);
    }

    @Test
    void fromContentAndMapRoundtripsProject() {
        Map<String, Object> withKey = new HashMap<>();
        withKey.put(PxChunk.PXCHUNK_ID, "id-1");
        withKey.put(PxChunk.PXCHUNK_PROJECT, "foo");

        PxChunk chunk = PxChunk.fromContentAndMap("content", withKey);
        assertThat(chunk.getProject()).isEqualTo("foo");

        Map<String, Object> withoutKey = new HashMap<>();
        withoutKey.put(PxChunk.PXCHUNK_ID, "id-2");

        PxChunk chunkWithout = PxChunk.fromContentAndMap("content", withoutKey);
        assertThat(chunkWithout.getProject()).isNull();
    }
}
