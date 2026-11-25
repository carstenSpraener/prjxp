package de.spraener.prjxp.chuno;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads a source file as text, tolerating legacy encodings.
 * <p>
 * Legacy projects (Java/JSP/SQL from the 2000s) are frequently ISO-8859-1 encoded. Reading
 * them strictly as UTF-8 throws a {@link MalformedInputException}, which the chunkers used to
 * swallow via {@code catch (IOException)}. The result was a file that silently disappeared
 * from the index — no error, no log, just a smaller file count.
 * <p>
 * Falling back to ISO-8859-1 keeps such a file (and its umlauts) in the index. ISO-8859-1
 * maps every byte to a character, so the fallback never fails. Binary files are still filtered
 * out afterwards by the chunkers' NUL-byte sniff.
 */
final class SourceTextReader {

    private SourceTextReader() {
    }

    static String read(File file) throws IOException {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (MalformedInputException notUtf8) {
            return Files.readString(file.toPath(), StandardCharsets.ISO_8859_1);
        }
    }
}
