package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;

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
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options(), normalizeArguments(args));
        } catch (ParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            printUsage();
            System.exit(1);
            return; // unreachable, satisfies compiler
        }

        if (!cmd.hasOption("server-url")) {
            System.err.println("ERROR: --server-url is required.");
            printUsage();
            System.exit(1);
            return;
        }

        String serverUrl = cmd.getOptionValue("server-url");
        int timeoutSeconds = Integer.parseInt(cmd.getOptionValue("timeout-seconds", "300"));

        log.info("Starting MCP Proxy: upstream={}, timeout={}s", serverUrl, timeoutSeconds);
        System.err.println("[mcp-proxy] Connected to upstream: " + serverUrl);

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
                        .handle((ignored, err) -> null)
                        .thenComposeAsync(ignored -> upstream.send(line), dispatchPool)
                        .thenAccept(responseLines -> responseLines.forEach(channelRef.get()::writeLine))
                        .exceptionally(err -> {
                            log.error("Request dispatch failed: {}", err.getMessage());
                            System.err.println("[mcp-proxy] Error: " + err.getMessage());
                            return null;
                        }));
            }

            @Override
            public void onEof() {
                log.info("stdin EOF — initiating graceful shutdown.");
                System.err.println("[mcp-proxy] stdin closed, closing upstream session...");

                requestChain.get()
                        .handle((ignored, err) -> null)
                        .thenCompose(ignored -> upstream.closeSession())
                        .whenComplete((v, err) -> {
                    if (err != null) {
                        log.warn("Session close failed: {}", err.getMessage());
                    }
                    log.info("Shutdown complete.");
                    System.err.println("[mcp-proxy] Shutdown complete.");

                    // Clean up and exit
                    dispatchPool.shutdownNow();
                    System.exit(0);
                        });
            }
        };

        StdioChannel channel = new StdioChannel(System.in, System.out, handler);
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
