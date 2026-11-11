package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeFilterTest {

    @Test
    void allReturnsEmptyMap() {
        assertThat(SourceTypeFilter.mimeFilters("all")).isEmpty();
    }

    @Test
    void nullAndUnknownValuesReturnEmptyMap() {
        assertThat(SourceTypeFilter.mimeFilters(null)).isEmpty();
        assertThat(SourceTypeFilter.mimeFilters("prose")).isEmpty();
    }

    @Test
    void codeExcludesDocMimes() {
        assertThat(SourceTypeFilter.mimeFilters("code"))
                .containsEntry(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf");
    }

    @Test
    void docsSelectsDocMimes() {
        assertThat(SourceTypeFilter.mimeFilters("docs"))
                .containsEntry(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
    }

    @Test
    void normalizationIsCaseAndWhitespaceInsensitive() {
        assertThat(SourceTypeFilter.mimeFilters(" DOCS "))
                .containsEntry(PxChunk.PXCHUNK_MIME_TYPE, "text/markdown|application/pdf");
        assertThat(SourceTypeFilter.mimeFilters("Code"))
                .containsEntry(PxChunk.PXCHUNK_MIME_TYPE, "!text/markdown|application/pdf");
    }
}
