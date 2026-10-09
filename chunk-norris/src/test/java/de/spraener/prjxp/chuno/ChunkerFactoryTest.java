package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.code.java.JavaCodeChunker;
import de.spraener.prjxp.chuno.code.java.JavaDependenciesChunker;
import de.spraener.prjxp.chuno.code.typescript.TypeScriptCodeChunker;
import de.spraener.prjxp.chuno.code.visualbasic.VisualBasicCodeChunker;
import de.spraener.prjxp.common.model.PxChunker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChunkerFactory} verifying that code-chunkers are registered via Language-Packs
 * while document-chunkers (Markdown/Text/PDF) remain on the annotation-scan path.
 */
@SpringBootTest(properties = {
        "prjxp.cli.enabled=false"
})
class ChunkerFactoryTest {

    @Autowired
    ChunkerFactory chunkerFactory;

    private List<PxChunker> createChunkers(String suffix) throws Exception {
        Path temp = Files.createTempFile("test", suffix);
        try {
            // Materialize first: AssertJ stream-assertions consume the stream,
            // so we assert on a List instead of re-using the Stream.
            return chunkerFactory.createChunker(temp.toFile()).toList();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void javaFileReturnsExactlyOneJavaCodeChunker() throws Exception {
        List<PxChunker> chunkers = createChunkers(".java");

        assertThat(chunkers).hasSize(1);
        assertThat(chunkers.get(0)).isInstanceOf(JavaCodeChunker.class);
    }

    @Test
    void tsFileReturnsTypeScriptCodeChunker() throws Exception {
        List<PxChunker> chunkers = createChunkers(".ts");

        assertThat(chunkers).hasSize(1);
        assertThat(chunkers.get(0)).isInstanceOf(TypeScriptCodeChunker.class);
    }

    @Test
    void vbFileReturnsVisualBasicCodeChunker() throws Exception {
        List<PxChunker> chunkers = createChunkers(".vb");

        assertThat(chunkers).hasSize(1);
        assertThat(chunkers.get(0)).isInstanceOf(VisualBasicCodeChunker.class);
    }

    @Test
    void mdFileReturnsDocumentChunkerNotCodeChunker() throws Exception {
        List<PxChunker> chunkers = createChunkers(".md");

        // Document-chunkers (Markdown) still registered via annotation-scan, so non-empty
        assertThat(chunkers).isNotEmpty();
        // But no code-chunker should be present
        for (PxChunker chunker : chunkers) {
            assertThat(chunker).isNotInstanceOf(JavaCodeChunker.class);
            assertThat(chunker).isNotInstanceOf(TypeScriptCodeChunker.class);
            assertThat(chunker).isNotInstanceOf(VisualBasicCodeChunker.class);
        }
    }

    @Test
    void listPostWalkChunkerContainsJavaDependenciesChunker() {
        var stream = chunkerFactory.listPostWalkChunker();

        assertThat(stream).anyMatch(c -> c instanceof JavaDependenciesChunker);
    }
}
