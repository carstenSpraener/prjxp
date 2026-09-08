package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PxChunkTest {

    @Test
    void metadataFieldKeyPrefixesCustomKeys() {
        assertThat(PxChunk.metadataFieldKey("symbol_fqn"))
                .isEqualTo("pxchunk_metadata.symbol_fqn");
    }

    @Test
    void metadataFieldKeyKeepsSystemKeys() {
        assertThat(PxChunk.metadataFieldKey("pxchunk_mimeType"))
                .isEqualTo("pxchunk_mimeType");
        assertThat(PxChunk.metadataFieldKey("pxchunk_id"))
                .isEqualTo("pxchunk_id");
    }

    @Test
    void metadataFieldKeyIsIdempotentForPrefixedKeys() {
        assertThat(PxChunk.metadataFieldKey("pxchunk_metadata.symbol_fqn"))
                .isEqualTo("pxchunk_metadata.symbol_fqn");
    }
}
