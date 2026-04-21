package de.spraener.prjxp.common.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
@Data
public class PrjXPConfig {
    // General via CLI

    // Chunk Norris
    private String chunoRootDir;
    private PrintWriter chunoOutput;
    private String chunoWhiteList;

    // Tibed
    public String tibedJsonlInputSource;
    private int tibedBatchSize = 50;
    private boolean tibedResetStore = false;

    // Golden Retriever
    private String grInputSource;
    private String grQuestion;
    private String grProjectSourceDir;

    // --- Embedding Sektion ---
    @Value("${embedding.ollama.url:http://192.168.1.228:11434}")
    private String embeddingOllamaUrl;
    @Value("${embedding.modelName:mxbai-embed-large}")
    private String embeddingModelName;
    @Value("${embedding.timeoutSecs:60}")
    private int embeddingTimeoutSecs;

    // Chroma Section
    @Value("${chroma.tenant:prjxp}")
    private String chromaTenant;
    @Value("${chroma.database:prjxp}")
    private String chromaDatabase;
    @Value("${chroma.collectionname:prjxp}")
    private String chromaCollectionname;
    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;

    // Chat
    @Value("${chat.gemini.api-key:NONE-SPECIFIED}")
    private String geminiApiKey;
    @Value("${chat.api-kind:ollama}")
    private String chatApiKind;
    @Value("${chat.modelName:gemini-2.5-flash}")
    private String chatModelName;
    @Value("${chat.apikey:lm-studio}")
    private String chatApiKey;
    @Value("${chat.api-url:http://localhost:11434}")
    private String chatApiUrl;


    public Stream<String> getJsonlStream(String inputSource) throws IOException {
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
