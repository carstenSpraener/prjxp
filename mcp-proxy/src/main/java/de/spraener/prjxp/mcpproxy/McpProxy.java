package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        // Parse CLI arguments
        Options options = new Options();
        options.addOption("server-url", true, "Upstream MCP server URL (e.g. http://localhost:7007/mcp)");
        options.addOption("timeout-seconds", true, "Upstream request timeout in seconds (default: 300)");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("ERROR: " + e.getMessage());
            new HelpFormatter().printHelp("mcp-proxy", options);
            System.exit(1);
            return; // unreachable, satisfies compiler
        }

        if (!cmd.hasOption("server-url")) {
            System.err.println("ERROR: --server-url is required.");
            new HelpFormatter().printHelp("mcp-proxy", options);
            System.exit(1);
            return;
        }

        String serverUrl = cmd.getOptionValue("server-url");
        int timeoutSeconds = Integer.parseInt(cmd.getOptionValue("timeout-seconds", "300"));

        log.info("Starting MCP Proxy: upstream={}, timeout={}s", serverUrl, timeoutSeconds);
        System.err.println("[mcp-proxy] Connected to upstream: " + serverUrl);

        // Create the HTTP upstream client
        StreamableHttpUpstream upstream = new StreamableHttpUpstream(serverUrl, timeoutSeconds);

        // Thread pool for dispatching requests to upstream (agents may call tools in parallel)
        ExecutorService dispatchPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dispatch");
            t.setDaemon(true);
            return t;
        });

        // Stdio channel: reads stdin, writes stdout/stderr
        final McpProxy proxy = new McpProxy();

        // Create handler first (avoids "variable may not have been initialized" compiler error)
        StdioChannel.LineHandler handler = new StdioChannel.LineHandler() {
            @Override
            public void onLine(String line) {
                if (line == null || line.trim().isEmpty()) return;

                CompletableFuture<List<String>> responseFuture = upstream.send(line);
                // We need a reference to the channel that will be created later.
                // Use a simple approach: write directly to System.out (synchronized)
                responseFuture.whenComplete((responseLines, err) -> {
                    if (err != null) {
                        log.error("Request dispatch failed: {}", err.getMessage());
                        System.err.println("[mcp-proxy] Error: " + err.getMessage());
                        return;
                    }
                    for (String respLine : responseLines) {
                        synchronized (System.out) {
                            System.out.println(respLine);
                            System.out.flush();
                        }
                    }
                });
            }

            @Override
            public void onEof() {
                log.info("stdin EOF — initiating graceful shutdown.");
                System.err.println("[mcp-proxy] stdin closed, closing upstream session...");

                // Terminate the upstream session
                upstream.closeSession().whenComplete((v, err) -> {
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

        // Start the stdin reader thread
        channel.start();

        // Wait for shutdown (stdin EOF triggers the flow)
        channel.eofFuture().join();
    }
}
