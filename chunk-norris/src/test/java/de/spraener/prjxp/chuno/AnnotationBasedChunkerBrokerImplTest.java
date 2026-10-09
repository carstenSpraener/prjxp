package de.spraener.prjxp.chuno;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AnnotationBasedChunkerBrokerImpl} verifying that after Phase 02,
 * code-chunkers (Java/TS/VB) are no longer discovered via annotation-scan,
 * while document-chunkers (Markdown/Text/PDF) still are.
 */
class AnnotationBasedChunkerBrokerImplTest {

    private static class TestableBroker extends AnnotationBasedChunkerBrokerImpl {
        protected TestableBroker(String rootPkg) {
            super(rootPkg);
        }
    }

    @Test
    void findPxChunkersForJavaFileIsEmpty() throws Exception {
        // Code-chunkers removed from annotation-scan, so .java returns empty
        TestableBroker broker = new TestableBroker("de.spraener.prjxp.chuno");
        ApplicationContext mockCtx = Mockito.mock(ApplicationContext.class);
        broker.setApplicationContext(mockCtx);

        Path temp = Files.createTempFile("test", ".java");
        try {
            var stream = broker.findPxChunkers(temp.toFile());
            assertThat(stream).isEmpty();
        } finally {
            Files.delete(temp);
        }
    }

    @Test
    void findPxChunkersForMarkdownFileIsNotEmpty() throws Exception {
        // Document-chunkers (Markdown) still registered via annotation-scan
        TestableBroker broker = new TestableBroker("de.spraener.prjxp.chuno");
        ApplicationContext mockCtx = Mockito.mock(ApplicationContext.class);
        broker.setApplicationContext(mockCtx);

        Path temp = Files.createTempFile("test", ".md");
        try {
            var stream = broker.findPxChunkers(temp.toFile());
            assertThat(stream).isNotEmpty();
        } finally {
            Files.delete(temp);
        }
    }
}
