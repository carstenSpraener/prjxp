package de.spraener.prjxp.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LineScanner}.
 */
class LineScannerTest {

    @TempDir
    Path tempDir;

    /** A file that has no {@code .groovy} neighbor, so no filter script is loaded. */
    private File plainFile() {
        return tempDir.resolve("in.txt").toFile();
    }

    private LineScanner scanner(String content, int n) throws Exception {
        return new LineScanner(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), plainFile(), n);
    }

    @Test
    void constructor_buffersFirstLine() throws Exception {
        LineScanner scanner = scanner("a\nb\nc\nd", 2);

        assertThat(scanner.getCurrentLine()).isEqualTo("a");
        assertThat(scanner.getGlobalLineIndex()).isZero();
        assertThat(scanner.getState()).isEqualTo(LineScanner.STATE.RUNNING);
        assertThat(scanner.getWindowSize()).isEqualTo(2);
        assertThat(scanner.getBufferCapacity()).isEqualTo(5); // 2n + 1
    }

    @Test
    void nextLine_walksToEof() throws Exception {
        LineScanner scanner = scanner("a\nb\nc\nd", 2);

        assertThat(scanner.nextLine()).isEqualTo("b");
        assertThat(scanner.nextLine()).isEqualTo("c");
        assertThat(scanner.nextLine()).isEqualTo("d");
        assertThat(scanner.nextLine()).isEqualTo(LineScanner.EOF);

        // Once EOF is reached, the scanner keeps returning the sentinel.
        assertThat(scanner.nextLine()).isEqualTo(LineScanner.EOF);
    }

    @Test
    void peek_windowAndBounds() throws Exception {
        LineScanner scanner = scanner("a\nb\nc\nd", 2);

        // Before the first nextLine, slot -1 is still unfilled (null -> EOF sentinel).
        assertThat(scanner.peek(-1)).isEqualTo(LineScanner.EOF);

        scanner.nextLine(); // current line is now "b"

        assertThat(scanner.peek(0)).isEqualTo("b");
        assertThat(scanner.peek(1)).isEqualTo("c");
        assertThat(scanner.peek(-1)).isEqualTo("a");

        assertThatThrownBy(() -> scanner.peek(3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void swapLines_swapsAndRejectsOutsideWindow() throws Exception {
        LineScanner scanner = scanner("a\nb\nc\nd", 2);

        assertThat(scanner.getCurrentLine()).isEqualTo("a");
        scanner.swapLines(0, 1); // swap current ("a") with next ("b")

        assertThat(scanner.getCurrentLine()).isEqualTo("b");
        assertThat(scanner.peek(1)).isEqualTo("a");

        assertThatThrownBy(() -> scanner.swapLines(0, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void peekNextNonEmpty_skipsBlanks() throws Exception {
        LineScanner scanner = scanner("a\n\n  \nb", 2);

        // Deviation from the phase doc: the source scans offsets [0, n) *including*
        // the current line, so from "a" it returns "a" itself.
        assertThat(scanner.peekNextNonEmpty()).isEqualTo("a");

        scanner.nextLine(); // current line is now "" (2nd line)
        assertThat(scanner.peekNextNonEmpty()).isEmpty(); // window = {"", "  "}

        scanner.nextLine(); // current line is now "  " (3rd line)
        assertThat(scanner.peekNextNonEmpty()).isEqualTo("b"); // skips the blank, finds "b"

        // Only blank lines -> ""
        LineScanner blanks = scanner("\n  \n", 2);
        assertThat(blanks.peekNextNonEmpty()).isEmpty();

        // A window full of EOF sentinels -> ""
        LineScanner atEof = scanner("a\n", 2);
        atEof.nextLine(); // current line is now EOF, window = {EOF, EOF}
        assertThat(atEof.peekNextNonEmpty()).isEmpty();
    }

    @Test
    void peekPrevNonEmpty_findsPrevious() throws Exception {
        LineScanner scanner = scanner("a\n\nb", 2);

        // Deviation from the phase doc: with input "a\n\nb" the blank line is reached
        // after ONE nextLine (two would already land on "b").
        scanner.nextLine(); // current line is now ""

        assertThat(scanner.peekPrevNonEmpty()).isEqualTo("a"); // skips the blank, finds "a"

        // An EOF sentinel inside the backward window is skipped as well.
        LineScanner shortFile = scanner("a\n", 2); // ring = [a, EOF, EOF]
        shortFile.nextLine(); // current line is now EOF
        assertThat(shortFile.peekPrevNonEmpty()).isEqualTo("a");
    }

    @Test
    void skipToLine_stopsAtMatch() throws Exception {
        LineScanner scanner = scanner("a\nb\nc\nd", 2);

        scanner.skipToLine(s -> "c".equals(s.getCurrentLine()));

        assertThat(scanner.getCurrentLine()).isEqualTo("c");
        assertThat(scanner.getGlobalLineIndex()).isEqualTo(2);
    }

    @Test
    void close_setsFinished() throws Exception {
        LineScanner scanner = null;
        try (LineScanner s = scanner("a\nb\nc", 2)) {
            assertThat(s.getState()).isEqualTo(LineScanner.STATE.RUNNING);
            scanner = s;
        }

        assertThat(scanner.getState()).isEqualTo(LineScanner.STATE.FINISHED);
    }

    @Test
    void groovySidecarScript_filtersLines() throws Exception {
        Path in = tempDir.resolve("in.txt");
        Files.writeString(in, "x\ny\nz");
        Files.writeString(tempDir.resolve("in.txt.groovy"), "return scanner.readRawLine();");

        LineScanner scanner = new LineScanner(
                new ByteArrayInputStream("x\ny\nz".getBytes(StandardCharsets.UTF_8)), in.toFile(), 2);

        assertThat(scanner.getCurrentLine()).isEqualTo("x");
        assertThat(scanner.nextLine()).isEqualTo("y");
    }
}
