package de.spraener.prjxp.tibed;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Data
@Component
public class TiBedConfig {
    private String inputSource;
    private int batchSize = 0;
    private boolean resetStore = false;
    // --- Embedding Sektion ---
    @Value("${tibed.ollama.url:http://192.168.1.228:11434}")
    private String ollamaUrl;

    @Value("${tibed.embedding.modelName:mxbai-embed-large}")
    private String embeddingModelName;

    @Value("${tibed.embedding.chroma.database:prjxp}")
    private String embedChromaDatabase;

    @Value("${tibed.embedding.chroma.tenant:prjxp}")
    private String embedChromaTenant;

    @Value("${tibed.embedding.collectionName:chunk_norris}")
    private String collectionName;

    // --- Vektorstore Sektion ---
    @Value("${tibed.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Value("${tibed.embedding.timeoutSecs:60}")
    private int embeddingTimeoutSecs;

    public Stream<String> getJsonlStream() throws IOException {
        InputStream inputStream;

        if (inputSource == null || "-".equals(inputSource) || inputSource == null || inputSource.isEmpty()) {
            // Nutze stdin (Standard Input)
            inputStream = System.in;
        } else {
            // Nutze die Datei
            Path path = Paths.get(inputSource);
            inputStream = Files.newInputStream(path);
        }

        // Erstelle einen BufferedReader und wandle ihn in einen Stream um
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

        // .lines() schließt den Reader automatisch, wenn der Stream geschlossen wird
        return reader.lines();
    }
}
