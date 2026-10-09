package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoredChunkTest {

    @Test
    void constructorAndAccessors() {
        PxChunk chunk = PxChunk.create(c -> c.setId("c1"));

        ScoredChunk scored = new ScoredChunk(chunk, 0.87);

        assertThat(scored.chunk()).isSameAs(chunk);
        assertThat(scored.score()).isEqualTo(0.87);
    }
}
