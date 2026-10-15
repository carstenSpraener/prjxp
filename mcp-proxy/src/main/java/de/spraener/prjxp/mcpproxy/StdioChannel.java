package de.spraener.prjxp.mcpproxy;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages stdin/stdout for the MCP proxy.
 * - Reads newline-delimited JSON-RPC messages from stdin on a dedicated thread.
 * - Writes responses to stdout in a synchronized manner (no interleaved lines).
 * - Signals EOF when stdin closes, triggering graceful shutdown.
 */
@Slf4j
public class StdioChannel implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stdio-reader");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CompletableFuture<Void> eofFuture = new CompletableFuture<>();

    /** Callback invoked when a complete line is read from stdin. */
    public interface LineHandler {
        void onLine(String line);

        void onEof();
    }

    private final InputStream stdinStream;
    private final OutputStream stdoutStream;
    private final LineHandler handler;

    public StdioChannel(InputStream stdin, OutputStream stdout, LineHandler handler) {
        this.stdinStream = stdin;
        this.stdoutStream = stdout;
        this.handler = handler;
    }

    /** Start the stdin reader thread. */
    public void start() {
        executor.submit(() -> readStdin());
    }

    private void readStdin() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stdinStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (closed.get()) break;
                handler.onLine(line);
            }
        } catch (IOException e) {
            log.warn("stdin read error: {}", e.getMessage());
        } finally {
            if (closed.compareAndSet(false, true)) {
                log.info("stdin EOF received, signaling shutdown.");
                handler.onEof();
                eofFuture.complete(null);
                executor.shutdown();
            }
        }
    }

    /** Write a line to stdout (synchronized to prevent interleaving). */
    public void writeLine(String line) {
        synchronized (stdoutStream) {
            try {
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(stdoutStream, StandardCharsets.UTF_8));
                writer.write(line);
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                log.error("Failed to write to stdout: {}", e.getMessage());
            }
        }
    }

    /** Write a line to stderr (for logging/debug, never interleaved with stdout). */
    public void writeError(String msg) {
        synchronized (System.err) {
            System.err.println(msg);
            System.err.flush();
        }
    }

    /** Return a future that completes when stdin EOF is received. */
    public CompletableFuture<Void> eofFuture() {
        return eofFuture;
    }

    /** Signal that the channel is shutting down. */
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            eofFuture.complete(null);
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
