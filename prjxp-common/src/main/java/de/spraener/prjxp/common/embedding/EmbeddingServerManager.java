package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.extern.java.Log;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

@Component("embeddingServerManager")
@Log
public class EmbeddingServerManager implements SmartLifecycle {

    private final PrjXPConfig cfg;
    private Process serverProcess;
    private boolean running = false;

    public EmbeddingServerManager(PrjXPConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void start() {
        // Skip if environment variable is set (useful for chunk-norris which doesn't need embeddings)
        if ("true".equals(System.getenv("SKIP_EMBEDDING_SERVER"))) {
            log.info("Embedding server auto-start skipped: SKIP_EMBEDDING_SERVER=true");
            return;
        }

        if (cfg.getEmbedding().getType() != PrjXPConfig.EmbeddingModelType.ONNX_LOCAL) {
            log.info("Embedding server auto-start skipped: mode is " + cfg.getEmbedding().getType());
            return;
        }

        String scriptPath = cfg.getEmbeddingServerScriptPath();
        Path script = Path.of(scriptPath);
        if (Files.isDirectory(script)) {
            script = script.resolve("embedding-server.py");
            scriptPath = script.toString();
        }
        String modelPath = cfg.getEmbeddingServerModelPath();
        String modelsDir = cfg.getEmbeddingServerModelsDir();
        int port = cfg.getEmbeddingServerPort();
        int startupTimeoutSecs = cfg.getEmbeddingServerStartupTimeoutSecs();
        boolean failOnStartupError = cfg.isEmbeddingServerFailOnStartupError();

        // Check if server is already running
        if (isHealthEndpointAvailable(port)) {
            log.info("Embedding server already running on port " + port + ", skipping auto-start");
            running = true;
            return;
        }

        // Verify files exist
        if (!Files.exists(script)) {
            throw new IllegalStateException("Embedding server script not found: " + scriptPath);
        }
        if (!Files.exists(Path.of(modelPath))) {
            throw new IllegalStateException("Embedding model not found: " + modelPath);
        }

        log.info("Starting embedding server: python3 " + scriptPath + " --model-path " + modelPath + " --models-dir " + modelsDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", scriptPath,
                    "--model-path", modelPath,
                    "--models-dir", modelsDir,
                    "--port", String.valueOf(port)
            );
            pb.redirectErrorStream(true);
            serverProcess = pb.start();
            Deque<String> recentOutput = new ArrayDeque<>();

            // Start background thread to consume process output
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (recentOutput) {
                            if (recentOutput.size() >= 100) {
                                recentOutput.removeFirst();
                            }
                            recentOutput.addLast(line);
                        }
                        String lower = line.toLowerCase();
                        if (lower.contains("loading") || lower.contains("ready") || lower.contains("error") || lower.contains("exception")) {
                            log.info("Embedding server: " + line);
                        } else {
                            log.fine("Embedding server: " + line);
                        }
                    }
                } catch (Exception e) {
                    // Process ended
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();

            // Wait for server to be ready (max 300 seconds — ONNX model loading is very slow in Docker)
            int waited = 0;
            while (!isHealthEndpointAvailable(port)) {
                if (!serverProcess.isAlive()) {
                    String outputTail;
                    synchronized (recentOutput) {
                        outputTail = String.join(System.lineSeparator(), recentOutput);
                    }
                    IllegalStateException startupError = new IllegalStateException(
                            "Embedding server process exited early with code " + serverProcess.exitValue() +
                                    (outputTail.isEmpty() ? "" : (". Last output:\n" + outputTail))
                    );
                    if (failOnStartupError) {
                        throw startupError;
                    }
                    log.warning("Embedding server failed during startup but application continues: " + startupError.getMessage());
                    running = false;
                    return;
                }
                Thread.sleep(1000);
                waited++;
                if (waited > startupTimeoutSecs) {
                    IllegalStateException timeoutError = new IllegalStateException(
                            "Embedding server failed to start within " + startupTimeoutSecs + " seconds on port " + port
                    );
                    if (failOnStartupError) {
                        stop();
                        throw timeoutError;
                    }
                    log.warning("Embedding server startup timeout exceeded, continuing without ready embedding server: " + timeoutError.getMessage());
                    running = false;
                    return;
                }
            }

            running = true;
            log.info("Embedding server started successfully on port " + port);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding server start interrupted", e);
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("Failed to start embedding server", e);
        }
    }

    @Override
    public void stop() {
        if (serverProcess != null) {
            log.info("Stopping embedding server...");
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                serverProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            serverProcess = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start early (low phase number) so it's ready before other beans
        return Integer.MIN_VALUE;
    }

    private boolean isHealthEndpointAvailable(int port) {
        try {
            URL url = new URL("http://localhost:" + port + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
