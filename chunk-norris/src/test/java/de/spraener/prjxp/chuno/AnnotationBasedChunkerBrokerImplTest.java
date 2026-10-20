package de.spraener.prjxp.chuno;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AnnotationBasedChunkerBrokerImpl} verifying that after Phase 02,
 * code-chunkers (Java/TS/VB) are no longer discovered via annotation-scan,
 * while document-chunkers (Markdown/Text/PDF) still are.
 * The language-agnostic {@link FullTextChunker} is registered via the scan as well
 * (it matches every file; gating happens later in {@link ChunkProcess}).
 */
class AnnotationBasedChunkerBrokerImplTest {

    private static class TestableBroker extends AnnotationBasedChunkerBrokerImpl {
        protected TestableBroker(String rootPkg) {
            super(rootPkg);
        }
    }

    private TestableBroker broker() {
        TestableBroker broker = new TestableBroker("de.spraener.prjxp.chuno");
        ApplicationContext mockCtx = Mockito.mock(ApplicationContext.class);
        // Class-level @Chunker registration resolves the bean from the context
        Mockito.when(mockCtx.getBean(FullTextChunker.class)).thenReturn(new FullTextChunker());
        broker.setApplicationContext(mockCtx);
        return broker;
    }

    @Test
    void findPxChunkersForJavaFileContainsNoCodeChunkers() throws Exception {
        // Code-chunkers removed from annotation-scan; only the language-agnostic FullTextChunker matches
        TestableBroker broker = broker();

        Path temp = Files.createTempFile("test", ".java");
        try {
            var chunkers = broker.findPxChunkers(temp.toFile()).toList();
            assertThat(chunkers).isNotEmpty();
            assertThat(chunkers).allSatisfy(c -> assertThat(c).isInstanceOf(FullTextChunker.class));
        } finally {
            Files.delete(temp);
        }
    }

    @Test
    void findPxChunkersForMarkdownFileIsNotEmpty() throws Exception {
        // Document-chunkers (Markdown) still registered via annotation-scan
        TestableBroker broker = broker();

        Path temp = Files.createTempFile("test", ".md");
        try {
            var stream = broker.findPxChunkers(temp.toFile());
            assertThat(stream).isNotEmpty();
        } finally {
            Files.delete(temp);
        }
    }
}
