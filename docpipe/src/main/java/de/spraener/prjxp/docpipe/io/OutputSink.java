package de.spraener.prjxp.docpipe.io;

/**
 * An abstraction over file output, similar to PrintWriter.
 * Implements AutoCloseable so it can be used in try-with-resources blocks.
 * This makes classes that produce output easily testable by substituting
 * a mock or in-memory implementation.
 */
public interface OutputSink extends AutoCloseable {

    /**
     * Prints a line followed by a newline character.
     */
    void println(String line);

    /**
     * Prints formatted output, analogous to PrintWriter.printf.
     */
    void printf(String format, Object... args);

    @Override
    void close();
}

