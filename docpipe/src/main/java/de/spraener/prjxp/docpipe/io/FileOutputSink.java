package de.spraener.prjxp.docpipe.io;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Default implementation of {@link OutputSink} that writes content to a file on the filesystem.
 * <p>
 * This implementation ensures that parent directories are created before opening a 
 * {@link PrintWriter} with UTF-8 encoding.
 * </p>
 */
public class FileOutputSink implements OutputSink {
    private final PrintWriter writer;

    /**
     * Constructs a new FileOutputSink that writes to the specified path.
     *
     * @param path the destination file path
     * @throws IOException if an error occurs while creating directories or opening the file
     */
    public FileOutputSink(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    /**
     * Prints a line followed by a newline character to the output file.
     *
     * @param line the text to print
     */
    @Override
    public void println(String line) {
        writer.println(line);
    }

    /**
     * Prints formatted output to the output file.
     *
     * @param format the format string
     * @param args the arguments to be formatted into the string
     */
    @Override
    public void printf(String format, Object... args) {
        writer.printf(format, args);
    }

    /**
     * Closes the output file writer.
     */
    @Override
    public void close() {
        writer.close();
    }
}

