package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * stdio → streamable-http MCP proxy.
 * <p>
 * Reads JSON-RPC messages from stdin (newline-delimited), forwards them as HTTP POST
 * to the upstream streamable-http MCP server, and relays responses back to stdout.
 * All logs go to stderr only.
 * </p>
 * <p>
 * Usage: java -jar mcp-proxy-all.jar --server-url http://localhost:7007/mcp [--timeout-seconds 300]
 * </p>
 */
@Slf4j
public class McpProxy {

    public static void main(String[] args) throws Exception {
        ParsedArgs parsed;
        try {
            parsed = parseArgs(args);
        } catch (ParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            printUsage();
            System.exit(1);
            return; // unreachable, satisfies compiler
        }
        run(parsed.serverUrl(), parsed.timeoutSeconds(),
                System.in, System.out, System.err, System::exit);
    }

    /**
     * Parses and validates the command line arguments.
     *
     * @throws ParseException if parsing fails or --server-url is missing
     */
    static ParsedArgs parseArgs(String[] args) throws ParseException {
        CommandLine cmd = new DefaultParser().parse(options(), normalizeArguments(args));
        if (!cmd.hasOption("server-url")) {
            throw new ParseException("--server-url is required.");
        }
        return new ParsedArgs(cmd.getOptionValue("server-url"),
                Integer.parseInt(cmd.getOptionValue("timeout-seconds", "300")));
    }

    /** Parsed command line arguments. */
    record ParsedArgs(String serverUrl, int timeoutSeconds) {
    }

    /**
     * The proxy event loop: reads lines from {@code in}, dispatches them to the upstream
     * (serially, preserving initialize/session ordering required by streamable HTTP servers)
     * and relays responses to {@code out}. Blocks until EOF on {@code in}; then closes the
     * upstream session and invokes {@code exit} with 0.
     * <p>
     * Streams and the exit callback are injectable so the loop can be tested with piped
     * streams and a local mock server instead of {@code System.in}/{@code System.exit}.
     */
    static void run(String serverUrl, int timeoutSeconds,
                    InputStream in, OutputStream out, PrintStream err,
                    IntConsumer exit) throws Exception {
        log.info("Starting MCP Proxy: upstream={}, timeout={}s", serverUrl, timeoutSeconds);
        err.println("[mcp-proxy] Connected to upstream: " + serverUrl);

        // Create the HTTP upstream client
        StreamableHttpUpstream upstream = new StreamableHttpUpstream(serverUrl, timeoutSeconds);

        // Serial dispatch preserves initialize/session ordering required by streamable HTTP servers.
        ExecutorService dispatchPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dispatch");
            t.setDaemon(true);
            return t;
        });

        AtomicReference<StdioChannel> channelRef = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> requestChain =
                new AtomicReference<>(CompletableFuture.completedFuture(null));

        StdioChannel.LineHandler handler = new StdioChannel.LineHandler() {
            @Override
            public void onLine(String line) {
                if (line == null || line.trim().isEmpty()) return;

                requestChain.updateAndGet(previous -> previous
                        .handle((ignored, e) -> null)
                        .thenComposeAsync(ignored -> upstream.send(line), dispatchPool)
                        .thenAccept(responseLines -> responseLines.forEach(channelRef.get()::writeLine))
                        .exceptionally(e -> {
                            log.error("Request dispatch failed: {}", e.getMessage());
                            err.println("[mcp-proxy] Error: " + e.getMessage());
                            return null;
                        }));
            }

            @Override
            public void onEof() {
                log.info("stdin EOF — initiating graceful shutdown.");
                err.println("[mcp-proxy] stdin closed, closing upstream session...");

                requestChain.get()
                        .handle((ignored, e) -> null)
                        .thenCompose(ignored -> upstream.closeSession())
                        .whenComplete((v, e) -> {
                            if (e != null) {
                                log.warn("Session close failed: {}", e.getMessage());
                            }
                            log.info("Shutdown complete.");
                            err.println("[mcp-proxy] Shutdown complete.");

                            // Clean up and exit
                            dispatchPool.shutdownNow();
                            exit.accept(0);
                        });
            }
        };

        StdioChannel channel = new StdioChannel(in, out, handler);
        channelRef.set(channel);

        // Start the stdin reader thread
        channel.start();

        // Wait for shutdown (stdin EOF triggers the flow)
        channel.eofFuture().join();
    }

    static Options options() {
        Options options = new Options();
        options.addOption(Option.builder()
                .longOpt("server-url")
                .hasArg()
                .desc("Upstream MCP server URL (e.g. http://localhost:7007/mcp)")
                .build());
        options.addOption(Option.builder()
                .longOpt("timeout-seconds")
                .hasArg()
                .desc("Upstream request timeout in seconds (default: 300)")
                .build());
        return options;
    }

    static String[] normalizeArguments(String[] args) {
        return Arrays.stream(args)
                .map(arg -> switch (arg) {
                    case "-server-url" -> "--server-url";
                    case "-timeout-seconds" -> "--timeout-seconds";
                    default -> arg;
                })
                .toArray(String[]::new);
    }

    private static void printUsage() {
        System.err.println("Usage: mcp-proxy --server-url <url> [--timeout-seconds <seconds>]");
    }
}
