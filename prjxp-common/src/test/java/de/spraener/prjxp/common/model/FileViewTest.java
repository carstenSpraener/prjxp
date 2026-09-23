package de.spraener.prjxp.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FileViewTest {

    @Test
    void errorFactoryFillsDefaults() {
        FileView view = FileView.error("src/Foo.java", "boom");

        assertThat(view.file()).isEqualTo("src/Foo.java");
        assertThat(view.language()).isNull();
        assertThat(view.chunkCount()).isZero();
        assertThat(view.content()).isNull();
        assertThat(view.truncated()).isFalse();
        assertThat(view.totalLines()).isNull();
        assertThat(view.returnedLines()).isNull();
        assertThat(view.candidates()).isEmpty();
        assertThat(view.error()).isEqualTo("boom");
    }

    @Test
    void ambiguousFactoryCarriesCandidates() {
        FileView view = FileView.ambiguous("Foo.java", List.of("a/Foo.java", "b/Foo.java"));

        assertThat(view.file()).isEqualTo("Foo.java");
        assertThat(view.language()).isNull();
        assertThat(view.chunkCount()).isZero();
        assertThat(view.content()).isNull();
        assertThat(view.truncated()).isFalse();
        assertThat(view.totalLines()).isNull();
        assertThat(view.returnedLines()).isNull();
        assertThat(view.candidates()).containsExactly("a/Foo.java", "b/Foo.java");
        assertThat(view.error()).isEqualTo("Ambiguous file path — 2 candidates");
    }
}
