package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link URLResolver}.
 */
class URLResolverTest {

    @TempDir
    Path baseDir;

    private Options optionsWithParam(String param) {
        Options options = mock(Options.class);
        when(options.param(anyInt())).thenReturn(param);
        return options;
    }

    @Test
    void idIsUrl() {
        assertThat(new URLResolver().getID()).isEqualTo("URL");
    }

    @Test
    void resolvesFileFromRelativeFileUrl() throws Exception {
        Files.createDirectories(baseDir.resolve("prompts"));
        Path included = baseDir.resolve("prompts").resolve("include.txt");
        Files.writeString(included, "URL-CONTENT");

        String result = new URLResolver().resolve(baseDir.toFile(), null, optionsWithParam("file:prompts/include.txt"));

        assertThat(result).isEqualTo("URL-CONTENT");
    }

    @Test
    void resolvesFileFromAbsoluteFileUrl() throws Exception {
        // The resolver concatenates baseDir + "/" + url.getFile(), so an absolute file: URL
        // is resolved relative to baseDir (the nested path must exist below baseDir).
        String absPath = "/abs/data/file.txt";
        Path target = baseDir.resolve("abs").resolve("data").resolve("file.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "ABS-CONTENT");

        String result = new URLResolver().resolve(baseDir.toFile(), null, optionsWithParam("file:" + absPath));

        assertThat(result).isEqualTo("ABS-CONTENT");
    }
}
