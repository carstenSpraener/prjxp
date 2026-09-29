package de.spraener.prjxp.docpipe.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoSurroundingCodeBlockTest {

    private final NoSurroundingCodeBlock filter = new NoSurroundingCodeBlock();

    @Test
    void name_returnsNoSurroundingCodeBlock() {
        assertThat(filter.name()).isEqualTo("noSurroundingCodeBlock");
    }

    @Test
    void filter_stripsSurroundingFencesKeepingInnerContent() {
        assertThat(filter.filter("```java\ncode\n```")).isEqualTo("code\n");
    }

    @Test
    void filter_plainContentWithoutFences_isUnchanged() {
        assertThat(filter.filter("plain text")).isEqualTo("plain text");
    }

    @Test
    void filter_startsButDoesNotEndWithFence_isUnchanged() {
        assertThat(filter.filter("```java\ncode")).isEqualTo("```java\ncode");
    }

    @Test
    void filter_endsButDoesNotStartWithFence_isUnchanged() {
        assertThat(filter.filter("code\n```")).isEqualTo("code\n```");
    }
}
