package de.spraener.prjxp.docpipe.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileOutputSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void constructor_createsMissingParentDirectories() throws Exception {
        Path file = tempDir.resolve("nested/dir/out.txt");

        try (FileOutputSink sink = new FileOutputSink(file)) {
            assertThat(file).exists();
        }

        assertThat(Files.readString(file)).isEmpty();
    }

    @Test
    void println_writesLineFollowedByNewline() throws Exception {
        Path file = tempDir.resolve("out.txt");

        FileOutputSink sink = new FileOutputSink(file);
        sink.println("hello");
        sink.close();

        assertThat(Files.readString(file)).isEqualTo("hello" + System.lineSeparator());
    }

    @Test
    void printf_writesFormattedOutputWithoutNewline() throws Exception {
        Path file = tempDir.resolve("out.txt");

        FileOutputSink sink = new FileOutputSink(file);
        sink.printf("value=%d", 42);
        sink.close();

        assertThat(Files.readString(file)).isEqualTo("value=42");
    }

    @Test
    void utf8Content_roundTripsCorrectly() throws Exception {
        Path file = tempDir.resolve("out.txt");

        FileOutputSink sink = new FileOutputSink(file);
        sink.println("Grüße & Umlaute");
        sink.close();

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("Grüße & Umlaute");
    }

    @Test
    void constructor_parentPathIsARegularFile_throwsIOException() throws Exception {
        Path existingFile = tempDir.resolve("plain.txt");
        Files.writeString(existingFile, "x");

        assertThatThrownBy(() -> new FileOutputSink(existingFile.resolve("child")))
                .isInstanceOf(java.io.IOException.class);
    }
}
