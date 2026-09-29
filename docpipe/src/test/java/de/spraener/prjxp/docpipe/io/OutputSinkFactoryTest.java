package de.spraener.prjxp.docpipe.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputSinkFactoryTest {

    @TempDir
    Path tempDir;

    private final OutputSinkFactory factory = new OutputSinkFactory();

    @Test
    void createSink_path_createsFileOutputSinkThatWritesToFile() throws Exception {
        Path file = tempDir.resolve("sub/out.txt");

        try (OutputSink sink = factory.createSink(file)) {
            assertThat(sink).isInstanceOf(FileOutputSink.class);
            sink.println("content");
        }

        assertThat(Files.readString(file)).contains("content");
    }

    @Test
    void createSink_stringVariant_delegatesToPathVariant() throws Exception {
        Path file = tempDir.resolve("out.txt");

        try (OutputSink sink = factory.createSink(file.toString())) {
            assertThat(sink).isInstanceOf(FileOutputSink.class);
            sink.println("content");
        }

        assertThat(Files.readString(file)).contains("content");
    }

    @Test
    void createSink_parentPathIsARegularFile_throwsIOException() throws Exception {
        Path existingFile = tempDir.resolve("plain.txt");
        Files.writeString(existingFile, "x");

        assertThatThrownBy(() -> factory.createSink(existingFile.resolve("child")))
                .isInstanceOf(IOException.class);
    }
}
