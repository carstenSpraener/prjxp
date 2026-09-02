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
        if (cfg.getEmbeddingModelType() != PrjXPConfig.EmbeddingModelType.ONNX_LOCAL) {
            log.info("Embedding server auto-start skipped: mode is " + cfg.getEmbeddingModelType());
            return;
        }

        String scriptPath = cfg.getEmbeddingServerScriptPath();
        String modelPath = cfg.getEmbeddingServerModelPath();
        String modelsDir = cfg.getEmbeddingServerModelsDir();
        int port = cfg.getEmbeddingServerPort();

        // Check if server is already running
        if (isHealthEndpointAvailable(port)) {
            log.info("Embedding server already running on port " + port + ", skipping auto-start");
            running = true;
            return;
        }

        // Verify files exist
        if (!Files.exists(Path.of(scriptPath))) {
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

            // Start background thread to consume process output
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.fine("Embedding server: " + line);
                    }
                } catch (Exception e) {
                    // Process ended
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();

            // Wait for server to be ready (max 30 seconds)
            int waited = 0;
            while (!isHealthEndpointAvailable(port)) {
                Thread.sleep(1000);
                waited++;
                if (waited > 30) {
                    stop();
                    throw new IllegalStateException("Embedding server failed to start within 30 seconds on port " + port);
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
