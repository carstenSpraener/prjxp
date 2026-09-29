package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CurrentFileResolver}. The resolver reads the file named by the
 * "currentFile" context entry, relative to the parent of the base (config) directory.
 */
class CurrentFileResolverTest {

    @TempDir
    Path projectRoot;

    private Options options() {
        return mock(Options.class);
    }

    @Test
    void idIsCurrentFile() {
        assertThat(new CurrentFileResolver().getID()).isEqualTo("currentFile");
    }

    @Test
    void readsCurrentFileRelativeToConfigDirParent() throws Exception {
        // baseDir is the .dp config dir; the current file lives in its parent (project root)
        Files.createDirectories(projectRoot.resolve(".dp"));
        Path source = projectRoot.resolve("src").resolve("Hello.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "public class Hello {}");

        File baseDir = projectRoot.resolve(".dp").toFile();
        Map<String, String> context = new HashMap<>();
        context.put("currentFile", "src/Hello.java");

        String result = new CurrentFileResolver().resolve(baseDir, context, options());

        assertThat(result).isEqualTo("public class Hello {}");
    }

    @Test
    void readsNestedCurrentFile() throws Exception {
        Files.createDirectories(projectRoot.resolve(".dp"));
        Path source = projectRoot.resolve("src").resolve("main").resolve("java").resolve("Deep.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package deep;\nclass Deep {}");

        File baseDir = projectRoot.resolve(".dp").toFile();
        Map<String, String> context = new HashMap<>();
        context.put("currentFile", "src/main/java/Deep.java");

        String result = new CurrentFileResolver().resolve(baseDir, context, options());

        assertThat(result).contains("package deep;");
    }
}
