package de.spraener.prjxp.docpipe.io;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Default implementation of OutputSink that writes to a file.
 */
public class FileOutputSink implements OutputSink {
    private final PrintWriter writer;

    public FileOutputSink(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    @Override
    public void println(String line) {
        writer.println(line);
    }

    @Override
    public void printf(String format, Object... args) {
        writer.printf(format, args);
    }

    @Override
    public void close() {
        writer.close();
    }
}

