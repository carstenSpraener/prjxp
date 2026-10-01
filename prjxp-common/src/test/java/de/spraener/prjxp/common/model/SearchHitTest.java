package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitTest {

    @Test
    void preview_nullOrBlank_returnsEmpty() {
        assertThat(SearchHit.preview(null)).isEmpty();
        assertThat(SearchHit.preview("   ")).isEmpty();
    }

    @Test
    void preview_shortString_unchanged() {
        assertThat(SearchHit.preview("hallo")).isEqualTo("hallo");
    }

    @Test
    void preview_longString_truncatedToSnippetMax() {
        assertThat(SearchHit.SNIPPET_MAX).isEqualTo(240);
        String longContent = "a".repeat(300);
        assertThat(SearchHit.preview(longContent)).hasSize(240);
    }

    @Test
    void from_parsesLineNumbersFromChunk() {
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("Foo.java");
            c.setFromLine("12");
            c.setToLine(" 34 ");
        });

        SearchHit hit = SearchHit.from(chunk, 0.9, "some snippet", "test");

        assertThat(hit.chunkId()).isEqualTo("c1");
        assertThat(hit.score()).isEqualTo(0.9);
        assertThat(hit.file()).isEqualTo("Foo.java");
        assertThat(hit.lineFrom()).isEqualTo(12);
        assertThat(hit.lineTo()).isEqualTo(34);
    }

    @Test
    void from_unparseableOrMissingLines_returnsNull() {
        PxChunk chunk = PxChunk.create(c -> c.setFromLine("abc"));

        SearchHit hit = SearchHit.from(chunk, 0.5, "s", "test");

        assertThat(hit.lineFrom()).isNull();
        assertThat(hit.lineTo()).isNull();
    }

    @Test
    void accessors_andMetadataMap() {
        Map<String, String> metadata = Map.of("k", "v");
        SearchHit hit = new SearchHit("id1", 0.75, "file.java", 10, 20, "snip", "src", metadata);

        assertThat(hit.chunkId()).isEqualTo("id1");
        assertThat(hit.score()).isEqualTo(0.75);
        assertThat(hit.file()).isEqualTo("file.java");
        assertThat(hit.lineFrom()).isEqualTo(10);
        assertThat(hit.lineTo()).isEqualTo(20);
        assertThat(hit.snippet()).isEqualTo("snip");
        assertThat(hit.source()).isEqualTo("src");
        assertThat(hit.metadata()).containsEntry("k", "v");
    }
}
