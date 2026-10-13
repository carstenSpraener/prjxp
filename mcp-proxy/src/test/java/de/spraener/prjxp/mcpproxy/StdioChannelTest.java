package de.spraener.prjxp.mcpproxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class StdioChannelTest {

    // =========================================================================
    // writeLine tests (synchronous, no threading needed)
    // =========================================================================

    @Test
    void writeLineWritesToOutputStream() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        StdioChannel channel = new StdioChannel(
                new ByteArrayInputStream(new byte[0]), baos, new StdioChannel.LineHandler() {
                    public void onLine(String line) {}
                    public void onEof() {}
                });

        channel.writeLine("{\"jsonrpc\":\"2.0\"}");
        assertThat(baos.toString(StandardCharsets.UTF_8))
                .contains("{\"jsonrpc\":\"2.0\"}");
    }

    @Test
    void writeLinePreservesNewline() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        StdioChannel channel = new StdioChannel(
                new ByteArrayInputStream(new byte[0]), baos, new StdioChannel.LineHandler() {
                    public void onLine(String line) {}
                    public void onEof() {}
                });

        channel.writeLine("line1");
        channel.writeLine("line2");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).isEqualTo("line1\nline2\n");
    }

    @Test
    void writeErrorGoesToStderr() throws Exception {
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(errBaos));

            StdioChannel channel = new StdioChannel(
                    new ByteArrayInputStream(new byte[0]), System.out, new StdioChannel.LineHandler() {
                        public void onLine(String line) {}
                        public void onEof() {}
                    });

            channel.writeError("error msg");
            assertThat(errBaos.toString(StandardCharsets.UTF_8))
                    .contains("error msg");
        } finally {
            System.setErr(originalErr);
        }
    }

    // =========================================================================
    // shutdown / close tests
    // =========================================================================

    @Test
    void shutdownCompletesEofFuture() throws Exception {
        StdioChannel channel = new StdioChannel(
                new ByteArrayInputStream(new byte[0]), System.out, new StdioChannel.LineHandler() {
                    public void onLine(String line) {}
                    public void onEof() {}
                });

        CompletableFuture<Void> future = channel.eofFuture();
        assertThat(future.isDone()).isFalse();

        channel.shutdown();
        assertThat(future.isDone()).isTrue();
    }

    @Test
    void closeDelegatesToShutdown() throws Exception {
        StdioChannel channel = new StdioChannel(
                new ByteArrayInputStream(new byte[0]), System.out, new StdioChannel.LineHandler() {
                    public void onLine(String line) {}
                    public void onEof() {}
                });

        channel.close();
        assertThat(channel.eofFuture().isDone()).isTrue();
    }

    @Test
    void doubleShutdownIsSafe() throws Exception {
        StdioChannel channel = new StdioChannel(
                new ByteArrayInputStream(new byte[0]), System.out, new StdioChannel.LineHandler() {
                    public void onLine(String line) {}
                    public void onEof() {}
                });

        channel.shutdown();
        channel.shutdown(); // should not throw
        assertThat(channel.eofFuture().isDone()).isTrue();
    }

    // =========================================================================
    // stdin reader thread tests (using PipedInputStream for testable stdin)
    // =========================================================================

    @Test
    void startDispatchesLinesFromStdin() throws Exception {
        PipedInputStream pipedIn = new PipedInputStream();
        PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

        List<String> receivedLines = new ArrayList<>();
        CountDownLatch lineLatch = new CountDownLatch(3);
        CountDownLatch eofLatch = new CountDownLatch(1);

        StdioChannel channel = new StdioChannel(pipedIn, System.out, new StdioChannel.LineHandler() {
            @Override
            public void onLine(String line) {
                receivedLines.add(line);
                lineLatch.countDown();
            }

            @Override
            public void onEof() {
                eofLatch.countDown();
            }
        });

        channel.start();

        // Write 3 lines to the piped input
        pipedOut.write("line1\n".getBytes(StandardCharsets.UTF_8));
        pipedOut.write("line2\n".getBytes(StandardCharsets.UTF_8));
        pipedOut.write("line3\n".getBytes(StandardCharsets.UTF_8));
        pipedOut.flush();

        // Wait for all 3 lines to be received
        assertThat(lineLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedLines).containsExactly("line1", "line2", "line3");

        // Close the piped output to trigger EOF
        pipedOut.close();

        // Wait for EOF signal
        assertThat(eofLatch.await(5, TimeUnit.SECONDS)).isTrue();

        channel.close();
    }

    @Test
    void eofFutureCompletesOnStdinClose() throws Exception {
        PipedInputStream pipedIn = new PipedInputStream();
        PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

        CountDownLatch eofLatch = new CountDownLatch(1);

        StdioChannel channel = new StdioChannel(pipedIn, System.out, new StdioChannel.LineHandler() {
            @Override
            public void onLine(String line) {}

            @Override
            public void onEof() {
                eofLatch.countDown();
            }
        });

        channel.start();

        // Close immediately — no data, just EOF
        pipedOut.close();

        assertThat(eofLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(channel.eofFuture().isDone()).isTrue();

        channel.close();
    }

    @Test
    void emptyLinesAreDispatched() throws Exception {
        PipedInputStream pipedIn = new PipedInputStream();
        PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

        List<String> receivedLines = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        StdioChannel channel = new StdioChannel(pipedIn, System.out, new StdioChannel.LineHandler() {
            @Override
            public void onLine(String line) {
                receivedLines.add(line);
                latch.countDown();
            }

            @Override
            public void onEof() {}
        });

        channel.start();

        // Write a line with an empty line in between
        pipedOut.write("hello\n\nworld\n".getBytes(StandardCharsets.UTF_8));
        pipedOut.flush();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // We expect: "hello", "", "world" — but latch only waits for 2
        assertThat(receivedLines).contains("hello", "world");

        pipedOut.close();
        channel.close();
    }
}
